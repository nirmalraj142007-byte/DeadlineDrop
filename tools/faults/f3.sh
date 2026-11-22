#!/usr/bin/env bash
# F3, database restart mid-rush (called by tools/run.sh; see its header for the stages).
#   inject  at T-90 s run "gcloud sql instances restart dd-pg --async", then poll the arm's
#           /actuator/health (which includes the database) every 2 s: the first failed check and the
#           first successful check after it. At the end, the restart operation's own start and end.
# No pre or post stage: the restart undoes itself. Writes RUN_DIR/fault-f3.json.
# The restart takes the whole Cloud SQL instance down, so it is reported against ourselves: whatever
# arm C does here is a measured result, not something to retry.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_stage
[ "${FAULT_STAGE}" = "inject" ] || exit 0
: "${T_EPOCH_MS:?T_EPOCH_MS missing}"

RESTART_BEFORE_T_MS=90000
POLL_S=2
POLL_MAX_S=900        # give up waiting for a healthy check after 15 min
NO_FAILURE_AFTER_S=180  # a restart that never fails a check: stop once healthy this long after the op ends
OUT="${RUN_DIR}/fault-${FAULT_NAME:-f3}.json"
BASE="$(arm_url "${ARM}")"

target=$(( T_EPOCH_MS - RESTART_BEFORE_T_MS ))
sleep_until_ms "${target}"
requested_ms="$(now_ms)"
op="$(gv sql instances restart "${SQL_INSTANCE}" --project="${PROJECT_ID}" --async --quiet --format='value(name)')"
returned_ms="$(now_ms)"
echo "restart of ${SQL_INSTANCE} requested at $(iso_ms "${requested_ms}") (operation ${op})"
json_write "${OUT}" fault "${FAULT_NAME:-f3}" sqlInstance "${SQL_INSTANCE}" operation "${op:-null}" \
  plannedAt "$(iso_ms "${target}")" restartRequestedAt "$(iso_ms "${requested_ms}")" \
  restartRequestedMinusTMs "$(( requested_ms - T_EPOCH_MS ))" restartCommandReturnedAt "$(iso_ms "${returned_ms}")"

# Polling goes on until the restart operation is DONE and a check after that is healthy: the first
# healthy check after a failure can come before the operation ends (day 17: A, B, C f3 stopped 9-20 s
# early, so their operationEndTime is null; the end times were read from gcloud afterwards).
# failedAfterRecovery counts failures after the first recovery (a second outage inside one restart).
first_fail_ms=""; healthy_after_ms=""; checks=0; failed=0; failed_after=0; op_done_ms=""
deadline_s=$(( $(date +%s) + POLL_MAX_S ))
while [ "$(date +%s)" -lt "${deadline_s}" ]; do
  t_ms="$(now_ms)"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${BASE}/actuator/health" || true)"
  checks=$(( checks + 1 ))
  if [ "${code}" = "200" ]; then
    if [ -n "${first_fail_ms}" ] && [ -z "${healthy_after_ms}" ]; then healthy_after_ms="${t_ms}"; fi
    if [ -z "${op_done_ms}" ] && [ -n "${op}" ]; then
      st="$(gv sql operations describe "${op}" --project="${PROJECT_ID}" --format='value(status)' 2>/dev/null || true)"
      [ "${st}" = "DONE" ] && op_done_ms="${t_ms}"
    fi
    if [ -n "${op_done_ms}" ]; then
      [ -n "${first_fail_ms}" ] && break
      # never saw a failure: stop once healthy this long after the operation ended
      if [ $(( t_ms - op_done_ms )) -ge $(( NO_FAILURE_AFTER_S * 1000 )) ]; then healthy_after_ms="${t_ms}"; break; fi
    fi
  else
    failed=$(( failed + 1 ))
    [ -n "${healthy_after_ms}" ] && failed_after=$(( failed_after + 1 ))
    if [ -z "${first_fail_ms}" ]; then
      first_fail_ms="${t_ms}"
      echo "first failed health check at $(iso_ms "${t_ms}") (HTTP ${code})"
    fi
  fi
  sleep "${POLL_S}"
done

op_start=""; op_end=""
if [ -n "${op}" ]; then
  op_start="$(gv sql operations describe "${op}" --project="${PROJECT_ID}" --format='value(startTime)' || true)"
  op_end="$(gv sql operations describe "${op}" --project="${PROJECT_ID}" --format='value(endTime)' || true)"
fi
json_write "${OUT}" healthChecks "${checks}" failedHealthChecks "${failed}" failedAfterRecovery "${failed_after}" \
  firstFailedHealthCheckAt "$([ -n "${first_fail_ms}" ] && iso_ms "${first_fail_ms}" || echo null)" \
  firstHealthyAfterRestartAt "$([ -n "${healthy_after_ms}" ] && iso_ms "${healthy_after_ms}" || echo null)" \
  firstHealthyAfterRestartMinusTMs "$([ -n "${healthy_after_ms}" ] && echo $(( healthy_after_ms - T_EPOCH_MS )) || echo null)" \
  unhealthyForMs "$([ -n "${first_fail_ms}" ] && [ -n "${healthy_after_ms}" ] && echo $(( healthy_after_ms - first_fail_ms )) || echo null)" \
  operationStartTime "${op_start:-null}" operationEndTime "${op_end:-null}"
# Exit non-zero only when the fault was not injected (run.sh then marks the run invalid). A database
# that stays unhealthy is a measured outcome, recorded above, not a reason to repeat the run.
[ -n "${op}" ] || { echo "restart was not accepted (no operation name)" >&2; exit 1; }
if [ -n "${healthy_after_ms}" ]; then
  echo "healthy again at $(iso_ms "${healthy_after_ms}")"
else
  echo "WARNING: no healthy check within ${POLL_MAX_S}s of the restart (recorded)" >&2
fi
