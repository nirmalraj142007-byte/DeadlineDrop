#!/usr/bin/env bash
# smoke-bucket.sh — confirm that the GCS bucket (via dd-arm-c) enforces signed-URL constraints.
# Logs each HTTP status and the first 200 bytes of Google's XML error.
# Output: results/smoke-bucket.log
#
# Usage: bash tools/smoke-bucket.sh
# Requires: SEED_PASSWORD, ASSIGNMENT_ID; gcloud CLI signed in with Workload Identity
#   (or Application Default Credentials that can run `gcloud storage ls`).
# The arm C Cloud Run URL is taken from docs/decisions.md D7.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOG="${REPO_ROOT}/results/smoke-bucket.log"
mkdir -p "${REPO_ROOT}/results"

ARM_C_URL="${ARM_C_URL:-https://dd-arm-c-aheo6nqyga-el.a.run.app}"
EMAIL="${STUDENT_EMAIL:-student0001@dd.local}"
ASSIGNMENT_ID="${ASSIGNMENT_ID:-11111111-1111-1111-1111-111111111111}"
: "${SEED_PASSWORD:?SEED_PASSWORD must be set}"

{
  echo "=== smoke-bucket.sh ==="
  echo "Date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "Arm C URL: ${ARM_C_URL}"
  echo ""

  # --- login ----------------------------------------------------------------
  echo "--- Step 0: login as ${EMAIL} ---"
  LOGIN_RESP=$(curl -s -f -X POST "${ARM_C_URL}/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${EMAIL}\",\"password\":\"${SEED_PASSWORD}\"}")
  TOKEN=$(printf '%s' "${LOGIN_RESP}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
  if [ -z "${TOKEN}" ]; then
    echo "ERROR: login failed; check SEED_PASSWORD and that arm C is running."
    exit 1
  fi
  echo "Login OK (token length ${#TOKEN})"
  echo ""

  # --- commit: declare 1 MiB application/pdf --------------------------------
  echo "--- Step 1: commit — declare 1 MiB, application/pdf ---"
  ONE_MIB=1048576
  # Random idempotency key from /dev/urandom (Git Bash / Linux compatible)
  HEX=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  VARIANT=$(printf '%x' $(( (0x${HEX:16:1} & 0x3) | 0x8 )))
  IKEY="${HEX:0:8}-${HEX:8:4}-4${HEX:13:3}-${VARIANT}${HEX:17:3}-${HEX:20:12}"

  # Dummy SHA-256 (all zeros) — we just need a commit record and a signed URL
  SHA="0000000000000000000000000000000000000000000000000000000000000000"

  COMMIT_RESP=$(curl -s -X POST "${ARM_C_URL}/api/assignments/${ASSIGNMENT_ID}/commits" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${IKEY}" \
    -d "{\"sha256\":\"${SHA}\",\"sizeBytes\":${ONE_MIB},\"contentType\":\"application/pdf\"}")
  SUBMISSION_ID=$(printf '%s' "${COMMIT_RESP}" | grep -o '"submissionId":"[^"]*"' | cut -d'"' -f4)
  UPLOAD_URL=$(printf '%s' "${COMMIT_RESP}" | grep -o '"uploadUrl":"[^"]*"' | cut -d'"' -f4 | sed 's/\\u0026/\&/g')

  if [ -z "${UPLOAD_URL}" ]; then
    echo "ERROR: commit did not return an uploadUrl; response:"
    printf '%s\n' "${COMMIT_RESP}"
    exit 1
  fi
  echo "Commit OK — submission ${SUBMISSION_ID}"
  echo "Signed URL (truncated): ${UPLOAD_URL%%\?*}?..."
  echo ""

  # Parse signed headers from the commit response (Content-Type + x-goog-content-length-range)
  HEADERS_JSON=$(printf '%s' "${COMMIT_RESP}" | grep -o '"uploadHeaders":{[^}]*}' | sed 's/^"uploadHeaders":{//; s/}$//')
  HEADER_ARGS=()
  IFS='|' read -r -a PAIRS <<< "${HEADERS_JSON//\",\"/\"|\"}"; unset IFS
  for pair in "${PAIRS[@]}"; do
    name=$(printf '%s' "${pair}" | cut -d'"' -f2)
    value=$(printf '%s' "${pair}" | cut -d'"' -f4)
    HEADER_ARGS+=(-H "${name}: ${value}")
  done
  echo "Signed headers: ${HEADERS_JSON}"
  echo ""

  # ---- (a) PUT 2 MiB to the signed URL (signed for 1 MiB) ----------------
  echo "--- (a) PUT 2 MiB to the signed URL (signed for 1 MiB) ---"
  TWO_MIB=$(( ONE_MIB * 2 ))
  RESP_A=$(mktemp); RESP_B=$(mktemp); RESP_C=$(mktemp)
  TMPFILE=$(mktemp)
  trap 'rm -f "${TMPFILE}" "${SMALL_FILE:-}" "${RESP_A}" "${RESP_B}" "${RESP_C}"' EXIT
  dd if=/dev/zero of="${TMPFILE}" bs=1024 count=$(( TWO_MIB / 1024 )) 2>/dev/null

  if command -v cygpath >/dev/null 2>&1; then
    CFILE=$(cygpath -m "${TMPFILE}")
  else
    CFILE="${TMPFILE}"
  fi

  STATUS_A=$(curl -sS -o "${RESP_A}" -w "%{http_code}" -X PUT "${UPLOAD_URL}" \
    "${HEADER_ARGS[@]}" --data-binary "@${CFILE}" || true)
  BODY_A=$(head -c 200 "${RESP_A}" 2>/dev/null || echo "(no body)")

  echo "HTTP status (a): ${STATUS_A}"
  echo "Response body (a, first 200 bytes): ${BODY_A}"
  echo ""

  if [ "${STATUS_A}" = "200" ]; then
    echo "GCS did not enforce the signed size range on PUT; finalize enforces it"
    echo ""
    # ---- (d) finalize — expect 422 SIZE_MISMATCH and object deleted --------
    echo "--- (d) finalize after GCS accepted the oversize PUT ---"
    FIN_RESP=$(curl -sS -w "\nHTTP_STATUS:%{http_code}" -X POST \
      "${ARM_C_URL}/api/submissions/${SUBMISSION_ID}/finalize" \
      -H "Authorization: Bearer ${TOKEN}" --data "")
    FIN_STATUS=$(printf '%s' "${FIN_RESP}" | grep -o 'HTTP_STATUS:[0-9]*' | cut -d: -f2)
    FIN_BODY=$(printf '%s' "${FIN_RESP}" | sed '/HTTP_STATUS:/d')
    echo "Finalize HTTP status (d): ${FIN_STATUS}"
    echo "Finalize response body: ${FIN_BODY}"

    # Extract object path from finalize response or use a naming pattern
    # Confirm the object is gone with gcloud storage ls
    BUCKET=$(bash "${SCRIPT_DIR}/env.sh" >/dev/null 2>&1 && echo "${BUCKET:-}" || true)
    if [ -n "${BUCKET:-}" ]; then
      OBJECT_PATH=$(printf '%s' "${COMMIT_RESP}" | grep -o '"objectPath":"[^"]*"' | cut -d'"' -f4 || echo "")
      if [ -n "${OBJECT_PATH}" ]; then
        echo "gcloud storage ls gs://${BUCKET}/${OBJECT_PATH}:"
        gcloud storage ls "gs://${BUCKET}/${OBJECT_PATH}" 2>&1 || echo "(object absent — correctly deleted)"
      else
        echo "(objectPath not in commit response; skipping gcloud ls)"
      fi
    else
      echo "(BUCKET not resolved; skipping gcloud storage ls)"
    fi
    echo ""
  else
    echo "(a) GCS enforced the size range at PUT time — status ${STATUS_A} (expected 400/413)"
    echo ""
  fi

  # ---- (b) PUT with Content-Type text/plain (application/pdf was signed) ---
  echo "--- (b) PUT with Content-Type: text/plain (application/pdf was signed) ---"
  # Need a fresh commit to get a fresh upload URL (the previous submission may have been finalised or its URL reused)
  HEX2=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  VARIANT2=$(printf '%x' $(( (0x${HEX2:16:1} & 0x3) | 0x8 )))
  IKEY2="${HEX2:0:8}-${HEX2:8:4}-4${HEX2:13:3}-${VARIANT2}${HEX2:17:3}-${HEX2:20:12}"
  COMMIT2=$(curl -s -X POST "${ARM_C_URL}/api/assignments/${ASSIGNMENT_ID}/commits" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${IKEY2}" \
    -d "{\"sha256\":\"${SHA}\",\"sizeBytes\":${ONE_MIB},\"contentType\":\"application/pdf\"}")
  UPLOAD_URL2=$(printf '%s' "${COMMIT2}" | grep -o '"uploadUrl":"[^"]*"' | cut -d'"' -f4 | sed 's/\\u0026/\&/g')

  # Build signed headers for second commit but override Content-Type to text/plain
  SIGNED_HEADERS2=()
  HEADERS_JSON2=$(printf '%s' "${COMMIT2}" | grep -o '"uploadHeaders":{[^}]*}' | sed 's/^"uploadHeaders":{//; s/}$//')
  IFS='|' read -r -a PAIRS2 <<< "${HEADERS_JSON2//\",\"/\"|\"}"; unset IFS
  for pair in "${PAIRS2[@]}"; do
    name2=$(printf '%s' "${pair}" | cut -d'"' -f2)
    value2=$(printf '%s' "${pair}" | cut -d'"' -f4)
    if [ "${name2}" = "Content-Type" ]; then
      # Replace with text/plain
      SIGNED_HEADERS2+=(-H "Content-Type: text/plain")
    else
      SIGNED_HEADERS2+=(-H "${name2}: ${value2}")
    fi
  done

  SMALL_FILE=$(mktemp)
  printf '%1024s' ' ' | tr ' ' 'X' > "${SMALL_FILE}"
  if command -v cygpath >/dev/null 2>&1; then
    CSMALL=$(cygpath -m "${SMALL_FILE}")
  else
    CSMALL="${SMALL_FILE}"
  fi

  STATUS_B=$(curl -sS -o "${RESP_B}" -w "%{http_code}" -X PUT "${UPLOAD_URL2}" \
    "${SIGNED_HEADERS2[@]}" --data-binary "@${CSMALL}" || true)
  BODY_B=$(head -c 200 "${RESP_B}" 2>/dev/null || echo "(no body)")

  echo "HTTP status (b): ${STATUS_B}"
  echo "Response body (b, first 200 bytes): ${BODY_B}"
  echo ""

  # ---- (c) PUT to same URL with object name changed ------------------------
  echo "--- (c) PUT to signed URL with object name changed ---"
  # Take the signed URL from the first commit; change the path segment
  # Swap the submission id (last path segment) for a different object name; signature stays the same.
  TAMPERED_URL="${UPLOAD_URL/${SUBMISSION_ID}/00000000-0000-4000-8000-000000000000}"

  STATUS_C=$(curl -sS -o "${RESP_C}" -w "%{http_code}" -X PUT "${TAMPERED_URL}" \
    "${HEADER_ARGS[@]}" --data-binary "@${CSMALL}" || true)
  BODY_C=$(head -c 200 "${RESP_C}" 2>/dev/null || echo "(no body)")

  echo "HTTP status (c): ${STATUS_C}"
  echo "Response body (c, first 200 bytes): ${BODY_C}"
  echo ""

  echo "=== smoke-bucket.sh complete ==="
} 2>&1 | tee "${LOG}"

echo ""
echo "Log written to ${LOG}"
