#!/usr/bin/env bash
# Arms A, B and C must run with identical Cloud Run settings. Describe each service, drop the
# metadata that always differs and the per-arm env vars, then diff pairwise.
# Silent + exit 0 when identical; prints the diff + exit 1 otherwise.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

PY="$(find_python)"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

ARMS=(a b c)
for arm in "${ARMS[@]}"; do
  gv run services describe "dd-arm-${arm}" --region="${REGION}" --project="${PROJECT_ID}" --format=export > "${TMP}/${arm}.raw.yaml"
  "${PY}" - "$(to_native_path "${TMP}/${arm}.raw.yaml")" "$(to_native_path "${TMP}/${arm}.yaml")" <<'PYEOF'
import re
import sys

# Env vars that legitimately differ per arm: dropped entirely (name and value).
PER_ARM_ENV = {
    "SPRING_PROFILES_ACTIVE", "DB_NAME", "INTERNAL_AUDIENCE", "SCHEDULER_SA_EMAIL", "CHAOS_ENABLED",
}
# Metadata that differs on every deploy or between services.
VOLATILE_KEY = re.compile(
    r"^\s*(uid|generation|resourceVersion|creationTimestamp|selfLink|revisionName|"
    r"client\.knative\.dev/nonce|run\.googleapis\.com/urls|run\.googleapis\.com/operation-id|"
    r"serving\.knative\.dev/creator|serving\.knative\.dev/lastModifier|"
    r"run\.googleapis\.com/client-name|run\.googleapis\.com/client-version):"
)
SERVICE_OR_REVISION_NAME = re.compile(r"^\s*name:\s*dd-arm-[abc](-\S+)?\s*$")
URL_LINE = re.compile(r"\.run\.app|https://dd-arm-")
ENV_ENTRY = re.compile(r"^(\s*)-\s+name:\s*(\S+)\s*$")

src, dst = sys.argv[1], sys.argv[2]
with open(src, encoding="utf-8") as f:
    lines = f.read().splitlines()

out = []
i = 0
while i < len(lines):
    line = lines[i]
    m = ENV_ENTRY.match(line)
    if m and m.group(2) in PER_ARM_ENV:
        base = len(m.group(1))
        i += 1
        while i < len(lines):
            nxt = lines[i]
            indent = len(nxt) - len(nxt.lstrip())
            if nxt.strip() and indent > base and not nxt.lstrip().startswith("- "):
                i += 1
            else:
                break
        continue
    if VOLATILE_KEY.match(line) or SERVICE_OR_REVISION_NAME.match(line) or URL_LINE.search(line):
        i += 1
        continue
    out.append(line)
    i += 1

with open(dst, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(out) + "\n")
PYEOF
done

status=0
for pair in "a b" "a c" "b c"; do
  set -- ${pair}
  if ! diff_out="$(diff -u --label "dd-arm-$1" --label "dd-arm-$2" "${TMP}/$1.yaml" "${TMP}/$2.yaml")"; then
    echo "${diff_out}"
    status=1
  fi
done
exit "${status}"
