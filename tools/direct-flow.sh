#!/usr/bin/env bash
# Arm B flow: direct-upload URL -> PUT to storage -> finalize. Usage: direct-flow.sh <base-url> [student-email]
# Needs SEED_PASSWORD in the environment. Prints each step's response.
set -euo pipefail

BASE="${1:?usage: direct-flow.sh <base-url> [student-email]}"
EMAIL="${2:-student0001@dd.local}"
ASSIGNMENT_ID="${ASSIGNMENT_ID:-11111111-1111-1111-1111-111111111111}"
: "${SEED_PASSWORD:?SEED_PASSWORD must be set}"

TOKEN=$(curl -s -f -X POST "${BASE}/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${SEED_PASSWORD}\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
[ -n "${TOKEN}" ] || { echo "login failed" >&2; exit 1; }

FILE="$(mktemp)"
trap 'rm -f "${FILE}"' EXIT
dd if=/dev/urandom of="${FILE}" bs=1024 count=1024 2>/dev/null
if command -v cygpath >/dev/null 2>&1; then CFILE=$(cygpath -m "${FILE}"); else CFILE="${FILE}"; fi
SIZE=$(wc -c < "${FILE}" | tr -d ' ')

echo "1. POST direct-uploads"
CREATED=$(curl -s -f -X POST "${BASE}/api/assignments/${ASSIGNMENT_ID}/direct-uploads" \
  -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" \
  -d "{\"sizeBytes\":${SIZE},\"contentType\":\"application/pdf\"}")
echo "${CREATED}" | sed 's/"uploadUrl":"[^"]*"/"uploadUrl":"<signed>"/'
UPLOAD_ID=$(echo "${CREATED}" | grep -o '"uploadId":"[^"]*"' | cut -d'"' -f4)
UPLOAD_URL=$(echo "${CREATED}" | grep -o '"uploadUrl":"[^"]*"' | cut -d'"' -f4 | sed 's/\\u0026/\&/g')

# Send every header the server returned: the cloud URL signs x-goog-content-length-range.
HEADERS_JSON=$(echo "${CREATED}" | grep -o '"uploadHeaders":{[^}]*}' | sed 's/^"uploadHeaders":{//; s/}$//')
HEADER_ARGS=()
IFS='|' read -r -a PAIRS <<< "${HEADERS_JSON//\",\"/\"|\"}"
for pair in "${PAIRS[@]}"; do
  name=$(echo "${pair}" | cut -d'"' -f2)
  value=$(echo "${pair}" | cut -d'"' -f4)
  HEADER_ARGS+=(-H "${name}: ${value}")
done

echo "2. PUT bytes to ${UPLOAD_URL%%\?*} with headers: ${HEADER_ARGS[*]}"
PUT_CODE=$(curl -sS -o /dev/null -w "%{http_code}" -X PUT "${UPLOAD_URL}" "${HEADER_ARGS[@]}" --data-binary "@${CFILE}")
echo "PUT status ${PUT_CODE}"
[ "${PUT_CODE}" = "200" ] || exit 1

echo "3. POST finalize"
# An empty body makes curl send Content-Length: 0, which Cloud Run's front end requires on POST.
curl -sS -f -X POST "${BASE}/api/assignments/${ASSIGNMENT_ID}/direct-uploads/${UPLOAD_ID}/finalize" \
  -H "Authorization: Bearer ${TOKEN}" --data ""
echo
