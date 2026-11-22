#!/usr/bin/env bash
# F3 without the signed commit receipt (phase 28, docs/decisions.md D12), arm C only.
#   pre     redeploy dd-arm-c (same image, same sweep mode) with RECEIPT_ENABLED=false, so a commit
#           that cannot reach the database answers 503 as before phase 28
#   inject  exactly tools/faults/f3.sh (restart at T-90 s, health checks)
#   post    redeploy with RECEIPT_ENABLED=true
# run.sh lets preflight's parity ignore RECEIPT_ENABLED for this fault only. Writes fault-f3-noreceipt.json.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_stage
[ "${ARM}" = "c" ] || { echo "f3-noreceipt applies to arm C only (the receipt is arm C's)" >&2; exit 1; }

case "${FAULT_STAGE}" in
  pre)
    redeploy_arm c RECEIPT_ENABLED=false
    json_write "${RUN_DIR}/fault-f3-noreceipt.json" receiptDisabledAt "$(iso_ms "$(now_ms)")"
    ;;
  inject)
    FAULT_NAME=f3-noreceipt bash "${FAULTS_DIR}/f3.sh"
    ;;
  post)
    redeploy_arm c RECEIPT_ENABLED=true
    json_write "${RUN_DIR}/fault-f3-noreceipt.json" receiptEnabledAgainAt "$(iso_ms "$(now_ms)")"
    ;;
esac
