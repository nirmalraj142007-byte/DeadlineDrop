#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

# shellcheck source=deploy/env.sh
source "${ROOT_DIR}/deploy/env.sh"

echo "=== Cloud Run Request Buffering Probe ==="

URL=$(gcloud run services describe dd-arm-a --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)")
if [ -z "${URL}" ]; then
  echo "Error: Could not retrieve URL for dd-arm-a" >&2
  exit 1
fi
echo "Service URL: ${URL}"

# 1. Create 20 MB random file probe.pdf
PROBE_FILE="${ROOT_DIR}/probe.pdf"
TARGET_SIZE=$((20 * 1024 * 1024))
if [ ! -f "${PROBE_FILE}" ] || [ "$(wc -c < "${PROBE_FILE}" | tr -d ' ')" -ne "${TARGET_SIZE}" ]; then
  echo "Generating 20 MB random file at ${PROBE_FILE}..."
  dd if=/dev/urandom of="${PROBE_FILE}" bs=1048576 count=20 2>/dev/null
fi
echo "probe.pdf size: $(wc -c < "${PROBE_FILE}" | tr -d ' ') bytes"

# 2. Log in as student0001
echo "Fetching seed password from Secret Manager..."
SEED_PASSWORD=$(gcloud secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")

echo "Logging in as student0001@dd.local..."
STUDENT_RESP=$(curl -s -f -X POST "${URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"student0001@dd.local\",\"password\":\"${SEED_PASSWORD}\"}")

STUDENT_TOKEN=$(echo "${STUDENT_RESP}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "${STUDENT_TOKEN}" ]; then
  echo "Error: Failed to obtain student access token" >&2
  exit 1
fi
echo "Student login successful."

# 3. Pick a fixed X-Request-Id
REQUEST_ID="${REQUEST_ID:-$(python -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "a1b2c3d4-e5f6-4a7b-8c9d-0123456789ab")}"
echo "Using X-Request-Id: ${REQUEST_ID}"

ASSIGNMENT_ID="${ASSIGNMENT_ID:-11111111-1111-1111-1111-111111111111}"
UPLOAD_URL="${URL}/api/assignments/${ASSIGNMENT_ID}/naive-submissions"

CURL_PROBE_FILE="${PROBE_FILE}"
if command -v cygpath >/dev/null 2>&1; then
  CURL_PROBE_FILE=$(cygpath -m "${PROBE_FILE}")
fi

RESP_FILE="${ROOT_DIR}/probe_response.json"
CURL_RESP_FILE="${RESP_FILE}"
if command -v cygpath >/dev/null 2>&1; then
  CURL_RESP_FILE=$(cygpath -m "${RESP_FILE}")
fi

# 4. Record clientStartMs and upload throttled to 125k (~1 Mbps)
clientStartMs=$(date +%s%3N)
echo "clientStartMs=${clientStartMs}"
echo "Uploading 20 MB throttled at 125k (~1 Mbps)..."

HTTP_CODE=$(curl -s -o "${CURL_RESP_FILE}" -w "%{http_code}" -X POST "${UPLOAD_URL}" \
  -H "Authorization: Bearer ${STUDENT_TOKEN}" \
  -H "X-Request-Id: ${REQUEST_ID}" \
  --limit-rate 125k \
  -F "file=@${CURL_PROBE_FILE};type=application/pdf")

# 5. Record clientEndMs
clientEndMs=$(date +%s%3N)
echo "clientEndMs=${clientEndMs}"
echo "Upload HTTP status: ${HTTP_CODE}"
cat "${RESP_FILE}"
echo ""

if [ "${HTTP_CODE}" != "201" ]; then
  echo "Error: Upload failed with status ${HTTP_CODE}" >&2
  exit 1
fi

# 6. Wait 60 s for Cloud Logging
echo "Waiting 60 seconds for Cloud Logging ingestion..."
sleep 60

# 7. gcloud logging read
echo "Reading Cloud Run logs for requestId ${REQUEST_ID}..."
FILTER="resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"dd-arm-a\" AND (jsonPayload.requestId=\"${REQUEST_ID}\" OR textPayload:\"${REQUEST_ID}\" OR \"${REQUEST_ID}\")"
gcloud logging read "${FILTER}" --project="${PROJECT_ID}" --format=json > "${ROOT_DIR}/probe_logs.json"

cat "${ROOT_DIR}/probe_logs.json"

echo ""
echo "=== Probe finished successfully ==="
echo "clientStartMs=${clientStartMs}"
echo "clientEndMs=${clientEndMs}"
echo "requestId=${REQUEST_ID}"
