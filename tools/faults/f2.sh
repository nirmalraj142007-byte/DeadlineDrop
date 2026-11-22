#!/usr/bin/env bash
# F2, the browser dies after the upload (called by tools/run.sh; see its header for the stages).
# Nothing is injected at run time: run.sh passes --skip-finalize-frac 0.10 to gen_schedule.py, so 10%
# of students commit and upload but never call finalize. Arm C must still record them (the
# reconciler moves COMMITTED rows whose object exists to UPLOADED); analyze.py adds an "f2" block.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_stage

if [ "${FAULT_STAGE}" = "inject" ]; then
  skipped="$("${PY}" -c "import json,sys; print(sum(1 for s in json.load(open(sys.argv[1], encoding='utf-8')) if s.get('skipFinalize')))" \
    "$(to_native_path "${RUN_DIR}/schedule/shard-all.json")" | tr -d '\r')"
  json_write "${RUN_DIR}/fault-f2.json" fault f2 skipFinalizeFrac "0.10" studentsSkippingFinalize "${skipped}" \
    injection "none at run time: the schedule marks these students skipFinalize"
  echo "F2: ${skipped} students in the schedule skip finalize"
fi
