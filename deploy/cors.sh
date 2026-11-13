#!/usr/bin/env bash
# Regenerate deploy/cors.json from every deployed dd-arm-* service and apply it to the bucket.
# Each service has two valid URLs (run.googleapis.com/urls); both are allowed origins.
# Does not deploy or change any service. Safe to run at any time.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"

PY="$(command -v python || command -v python3)"
SERVICES_JSON="$(mktemp)"
trap 'rm -f "${SERVICES_JSON}"' EXIT
gcloud run services list --region="${REGION}" --project="${PROJECT_ID}" --format=json > "${SERVICES_JSON}"

"${PY}" - "${SERVICES_JSON}" "${SCRIPT_DIR}/cors.json" <<'PYEOF'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as f:
    services = json.load(f)
origins = []
for s in services:
    if not s["metadata"]["name"].startswith("dd-arm-"):
        continue
    urls = json.loads(s["metadata"]["annotations"].get("run.googleapis.com/urls", "[]"))
    urls.append(s["status"]["url"])
    for u in urls:
        if u not in origins:
            origins.append(u)
if not origins:
    sys.exit("no dd-arm-* services found; refusing to write an empty CORS policy")
origins.sort()
cors = [{
    "origin": origins,
    "method": ["PUT", "GET"],
    "responseHeader": ["Content-Type", "Content-Disposition", "x-goog-content-length-range"],
    "maxAgeSeconds": 3600,
}]
with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as f:
    json.dump(cors, f, indent=2)
    f.write("\n")
PYEOF

gcloud storage buckets update "gs://${BUCKET}" --cors-file="${SCRIPT_DIR}/cors.json" --project="${PROJECT_ID}"
echo "Bucket CORS updated."
