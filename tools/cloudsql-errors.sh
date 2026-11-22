#!/usr/bin/env bash
# Pull the Cloud SQL PostgreSQL log lines at WARNING or above (and any FATAL/ERROR line, whatever its
# severity label) for the window of one run, into <run_dir>/cloudsql-errors.txt:
#   bash tools/cloudsql-errors.sh <run_dir>
# Window: meta.json startedAt to T + graceS + 2 min + the export wait + 5 min. The file always gets a
# header (instance, window, filter, line count), so an empty result is visibly empty, not missing.
# Phase 33 uses it for the scale10 run, where 10 x 4 + 1 x 2 + 1 x 2 = 44 connections approach
# max_connections - 5: any "remaining connection slots are reserved" line means the budget was hit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

RUN_DIR="${1:-}"
[ -n "${RUN_DIR}" ] && [ -f "${RUN_DIR}/meta.json" ] || { echo "Usage: $0 <run_dir with meta.json>" >&2; exit 1; }
PY="$(find_python)"
OUT="${RUN_DIR}/cloudsql-errors.txt"

read -r FROM TO < <("${PY}" - "$(to_native_path "${RUN_DIR}/meta.json")" <<'PYEOF' | tr -d '\r'
import datetime as d, json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
if not m.get("startedAt") or not m.get("T"):
    sys.exit("meta.json has no startedAt or T: the run did not finish")
iso = lambda s: d.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=d.timezone.utc)
end = iso(m["T"]) + d.timedelta(seconds=int(m.get("graceS", 300)) + 120
                                + int((m.get("exportWait") or {}).get("waitedS", 0)) + 300)
print(m["startedAt"], end.strftime("%Y-%m-%dT%H:%M:%SZ"))
PYEOF
)
[ -n "${FROM}" ] && [ -n "${TO}" ] || { echo "Error: could not read the run window from ${RUN_DIR}/meta.json" >&2; exit 1; }

FILTER="resource.type=\"cloudsql_database\" AND resource.labels.database_id=\"${PROJECT_ID}:${SQL_INSTANCE}\" \
AND logName=\"projects/${PROJECT_ID}/logs/cloudsql.googleapis.com%2Fpostgres.log\" \
AND timestamp>=\"${FROM}\" AND timestamp<=\"${TO}\" \
AND (severity>=WARNING OR textPayload:\"FATAL\" OR textPayload:\"ERROR\" OR textPayload:\"remaining connection slots\")"

TMP="$(mktemp)"
trap 'rm -f "${TMP}"' EXIT
# 60 s is plenty for one run's window; a timeout is a failure, never an empty (clean-looking) file.
timeout 60 gcloud logging read "${FILTER}" --project="${PROJECT_ID}" --order=asc --limit=10000 \
  --format='value(timestamp,severity,textPayload)' > "${TMP}" \
  || { echo "Error: gcloud logging read failed or timed out for ${FROM}..${TO}" >&2; exit 1; }
N="$(grep -c . "${TMP}" || true)"
{
  echo "# Cloud SQL ${PROJECT_ID}:${SQL_INSTANCE}, postgres.log, ${FROM} to ${TO} (UTC)"
  echo "# filter: severity>=WARNING or textPayload contains FATAL, ERROR or the reserved-connection-slots message"
  echo "# pulled $(date -u +%Y-%m-%dT%H:%M:%SZ) by tools/cloudsql-errors.sh; ${N} lines"
  tr -d '\r' < "${TMP}"
} > "${OUT}"
echo "wrote ${OUT} (${N} lines, ${FROM} to ${TO})"
