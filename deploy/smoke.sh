#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy/env.sh
source "${SCRIPT_DIR}/env.sh"

ARM="${1:-}"
if [ -z "$ARM" ]; then
  echo "Usage: $0 <a|b|c>"
  exit 1
fi

SERVICE_NAME="dd-arm-${ARM}"
echo "=== Smoke Testing ${SERVICE_NAME} in ${REGION} ==="

URL=$(gcloud run services describe "${SERVICE_NAME}" --region="${REGION}" --project="${PROJECT_ID}" --format="value(status.url)")
if [ -z "${URL}" ]; then
  echo "Error: Could not retrieve URL for service ${SERVICE_NAME}" >&2
  exit 1
fi
echo "Service URL: ${URL}"

# 1. Health check
echo "1. Checking health endpoint..."
HEALTH=$(curl -s -f "${URL}/actuator/health")
echo "Health response: ${HEALTH}"
if ! echo "${HEALTH}" | grep -q '"UP"'; then
  echo "Health check failed: status is not UP" >&2
  exit 1
fi
echo "Health check passed: UP"

# 2. Teacher login
echo "2. Fetching seed password from Secret Manager..."
SEED_PASSWORD=$(gcloud secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")

echo "Logging in as teacher@dd.local..."
TEACHER_RESP=$(curl -s -f -X POST "${URL}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"teacher@dd.local\",\"password\":\"${SEED_PASSWORD}\"}")

TEACHER_TOKEN=$(echo "${TEACHER_RESP}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
if [ -z "${TEACHER_TOKEN}" ]; then
  echo "Failed to extract access token for teacher login!" >&2
  exit 1
fi
echo "Teacher login successful."

# 3. Arm A specific check: student login, 1 MB multipart upload, verify in GCS
if [ "$ARM" = "a" ]; then
  echo "3. Arm A multipart upload verification..."
  echo "Logging in as student0001@dd.local..."
  STUDENT_RESP=$(curl -s -f -X POST "${URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"student0001@dd.local\",\"password\":\"${SEED_PASSWORD}\"}")

  STUDENT_TOKEN=$(echo "${STUDENT_RESP}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
  if [ -z "${STUDENT_TOKEN}" ]; then
    echo "Failed to extract access token for student login!" >&2
    exit 1
  fi
  echo "Student login successful."

  TEST_FILE="$(mktemp 2>/dev/null || echo "smoke_upload.pdf")"
  RESP_FILE="$(mktemp 2>/dev/null || echo "smoke_response.json")"

  if command -v cygpath >/dev/null 2>&1; then
    CURL_TEST_FILE=$(cygpath -m "${TEST_FILE}")
    CURL_RESP_FILE=$(cygpath -m "${RESP_FILE}")
  else
    CURL_TEST_FILE="${TEST_FILE}"
    CURL_RESP_FILE="${RESP_FILE}"
  fi

  # Generate exactly 1 MB file (1048576 bytes)
  dd if=/dev/urandom of="${TEST_FILE}" bs=1048576 count=1 2>/dev/null

  ASSIGNMENT_ID="${ASSIGNMENT_ID:-11111111-1111-1111-1111-111111111111}"
  UPLOAD_URL="${URL}/api/assignments/${ASSIGNMENT_ID}/naive-submissions"

  echo "Uploading 1 MB multipart file to ${UPLOAD_URL}..."
  HTTP_CODE=$(curl -s -o "${CURL_RESP_FILE}" -w "%{http_code}" -X POST "${UPLOAD_URL}" \
    -H "Authorization: Bearer ${STUDENT_TOKEN}" \
    -F "file=@${CURL_TEST_FILE};type=application/pdf")

  if [ "${HTTP_CODE}" != "201" ]; then
    echo "Multipart upload failed with HTTP status ${HTTP_CODE}:" >&2
    cat "${RESP_FILE}" >&2
    rm -f "${TEST_FILE}" "${RESP_FILE}"
    exit 1
  fi

  echo "Upload succeeded with HTTP 201:"
  cat "${RESP_FILE}"
  echo ""
  rm -f "${TEST_FILE}" "${RESP_FILE}"

  echo "Listing stored object in GCS bucket gs://${BUCKET}..."
  gcloud storage ls "gs://${BUCKET}/a/${ASSIGNMENT_ID}/**" --project="${PROJECT_ID}"
fi

echo "=== Smoke test for arm ${ARM} passed successfully! ==="
