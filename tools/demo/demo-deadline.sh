#!/usr/bin/env bash
# Usage: demo-deadline.sh <seconds>
# Moves Lab 4's deadline to now + <seconds> in BOTH demo databases and prints it in IST.
# Demo tooling: it writes straight to the databases with psql. The product never edits a deadline
# this way, and no fairness decision uses SQL now(); this only sets the stage for the demo.
# Arm C's database computes the instant; arm B gets the very same instant, so both windows
# count down to the same moment.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

secs="${1:-}"
if ! [[ "$secs" =~ ^[0-9]+$ ]] || [ "$secs" -le 0 ]; then
  echo "usage: demo-deadline.sh <seconds>  (positive integer)" >&2
  exit 1
fi

id_c="$(lab4_id C)"; id_b="$(lab4_id B)"
: "${id_c:?no Lab 4 id for arm C}"
: "${id_b:?no Lab 4 id for arm B}"

# -q drops the "UPDATE 1" command tag, so only the RETURNING value is printed.
deadline="$(psql_db dd_c -q -c "UPDATE assignments SET deadline_at = now() + make_interval(secs => ${secs}) WHERE id = '${id_c}' RETURNING deadline_at" | tr -d '\r\n')"
[ -n "$deadline" ] || { echo "ERROR: Lab 4 ${id_c} not found in dd_c" >&2; exit 1; }
shown_b="$(psql_db dd_b -q -c "UPDATE assignments SET deadline_at = '${deadline}'::timestamptz WHERE id = '${id_b}' RETURNING to_char(deadline_at AT TIME ZONE 'Asia/Kolkata', 'DD Mon YYYY HH24:MI:SS')" | tr -d '\r\n')"
[ -n "$shown_b" ] || { echo "ERROR: Lab 4 ${id_b} not found in dd_b" >&2; exit 1; }

echo "Lab 4 deadline (both arms): ${shown_b} IST"
