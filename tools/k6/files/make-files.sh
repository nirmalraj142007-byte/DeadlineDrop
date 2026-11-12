#!/usr/bin/env bash
# Creates test files for each size bucket (1, 2, 5, 10, 15, 25 MB, capped by MAX_MB).
set -euo pipefail

MAX_MB="${MAX_MB:-25}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-${SCRIPT_DIR}/out}"

mkdir -p "${OUT_DIR}"

SIZES=(1 2 5 10 15 25)

for sz in "${SIZES[@]}"; do
  if [ "${sz}" -le "${MAX_MB}" ]; then
    target="${OUT_DIR}/${sz}mb.bin"
    target_bytes=$(( sz * 1048576 ))
    if [ -f "${target}" ]; then
      actual_bytes=$(wc -c < "${target}" | tr -d ' ')
      if [ "${actual_bytes}" -eq "${target_bytes}" ]; then
        echo "File ${target} already exists (${sz} MB)"
        continue
      fi
    fi
    echo "Creating ${target} (${sz} MB)..."
    dd if=/dev/urandom of="${target}" bs=1048576 count="${sz}" status=none
  fi
done

echo "make-files complete in ${OUT_DIR}"
