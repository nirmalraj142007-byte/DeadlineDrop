#!/usr/bin/env bash
# End-to-end check of the hash commitment in the cloud: login -> commit (sha256 of a random 1 MB
# file) -> PUT the bytes to the signed URL -> finalize (UPLOADED) -> wait until Cloud Scheduler's
# drain has verified the stored bytes (event submission_verified in Cloud Logging).
# Usage: smoke-flow.sh <arm>   (only arm c has the commit flow and the scheduler)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"

ARM="${1:?usage: smoke-flow.sh <arm>}"
if [ "${ARM}" != "c" ]; then
  echo "Error: the commit -> upload -> finalize -> verify flow exists only on arm c." >&2
  exit 1
fi
SERVICE_NAME="dd-arm-${ARM}"
ASSIGNMENT_ID="${ASSIGNMENT_ID:-11111111-1111-1111-1111-111111111111}"
EMAIL="${EMAIL:-student0001@dd.local}"
VERIFY_TIMEOUT_S=150
POLL_EVERY_S=10

URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)")
[ -n "${URL}" ] || { echo "Error: no URL for ${SERVICE_NAME}" >&2; exit 1; }
SEED_PASSWORD=$(gcloud secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")

echo "1. Login as ${EMAIL} on ${URL}"
TOKEN=$(curl -sS -f --max-time 15 -X POST "${URL}/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${SEED_PASSWORD}\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[ -n "${TOKEN}" ] || { echo "Error: login failed" >&2; exit 1; }

FILE="$(mktemp)"
trap 'rm -f "${FILE}"' EXIT
dd if=/dev/urandom of="${FILE}" bs=1048576 count=1 2>/dev/null
if command -v cygpath >/dev/null 2>&1; then CFILE=$(cygpath -m "${FILE}"); else CFILE="${FILE}"; fi
SIZE=$(wc -c < "${FILE}" | tr -d ' ')
SHA=$(sha256sum "${FILE}" | cut -d' ' -f1)

# Random version-4 UUID from /dev/urandom (Git Bash on Windows has no uuidgen).
HEX=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
VARIANT=$(printf '%x' $(( (0x${HEX:16:1} & 0x3) | 0x8 )))
IDEMPOTENCY_KEY="${HEX:0:8}-${HEX:8:4}-4${HEX:13:3}-${VARIANT}${HEX:17:3}-${HEX:20:12}"

echo "2. Commit sha256 ${SHA} (${SIZE} bytes), Idempotency-Key ${IDEMPOTENCY_KEY}"
COMMITTED=$(curl -sS -f --max-time 15 -X POST "${URL}/api/assignments/${ASSIGNMENT_ID}/commits" \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
  -d "{\"sha256\":\"${SHA}\",\"sizeBytes\":${SIZE},\"contentType\":\"application/pdf\"}")
SUBMISSION_ID=$(echo "${COMMITTED}" | grep -o '"submissionId":"[^"]*"' | cut -d'"' -f4)
UPLOAD_URL=$(echo "${COMMITTED}" | grep -o '"uploadUrl":"[^"]*"' | cut -d'"' -f4 | sed 's/\\u0026/\&/g')
[ -n "${SUBMISSION_ID}" ] && [ -n "${UPLOAD_URL}" ] || { echo "Error: commit response without id or URL: ${COMMITTED}" >&2; exit 1; }
echo "   submissionId ${SUBMISSION_ID}"

# Send every header the server returned: the signed URL covers x-goog-content-length-range.
HEADERS_JSON=$(echo "${COMMITTED}" | grep -o '"uploadHeaders":{[^}]*}' | sed 's/^"uploadHeaders":{//; s/}$//')
HEADER_ARGS=()
IFS='|' read -r -a PAIRS <<< "${HEADERS_JSON//\",\"/\"|\"}"
for pair in "${PAIRS[@]}"; do
  HEADER_ARGS+=(-H "$(echo "${pair}" | cut -d'"' -f2): $(echo "${pair}" | cut -d'"' -f4)")
done

echo "3. PUT bytes to ${UPLOAD_URL%%\?*}"
PUT_CODE=$(curl -sS --max-time 60 -o /dev/null -w "%{http_code}" -X PUT "${UPLOAD_URL}" "${HEADER_ARGS[@]}" --data-binary "@${CFILE}")
[ "${PUT_CODE}" = "200" ] || { echo "Error: PUT returned ${PUT_CODE}" >&2; exit 1; }

echo "4. Finalize"
# An empty body makes curl send Content-Length: 0, which Cloud Run's front end requires on POST.
FINALIZED=$(curl -sS -f --max-time 15 -X POST "${URL}/api/submissions/${SUBMISSION_ID}/finalize" \
  -H "Authorization: Bearer ${TOKEN}" --data "")
STATUS=$(echo "${FINALIZED}" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
echo "   status ${STATUS}"
[ "${STATUS}" = "UPLOADED" ] || { echo "Error: expected UPLOADED after finalize: ${FINALIZED}" >&2; exit 1; }
FINALIZED_AT=$(date +%s)

echo "5. Waiting up to ${VERIFY_TIMEOUT_S} s for submission_verified in Cloud Logging"
FILTER="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SERVICE_NAME}\" AND jsonPayload.event=\"submission_verified\" AND jsonPayload.submissionId=\"${SUBMISSION_ID}\""
while :; do
  FOUND=$(gcloud logging read "${FILTER}" --project="${PROJECT_ID}" --freshness=15m --limit=1 --format="value(timestamp)")
  ELAPSED=$(( $(date +%s) - FINALIZED_AT ))
  if [ -n "${FOUND}" ]; then
    echo "VERIFIED ${SUBMISSION_ID} after ${ELAPSED} s (log timestamp ${FOUND})"
    exit 0
  fi
  if [ "${ELAPSED}" -ge "${VERIFY_TIMEOUT_S}" ]; then
    echo "Error: no submission_verified for ${SUBMISSION_ID} after ${ELAPSED} s" >&2
    exit 1
  fi
  sleep "${POLL_EVERY_S}"
done
