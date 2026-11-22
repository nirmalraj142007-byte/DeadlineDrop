#!/usr/bin/env bash
# F1, instance crash mid-rush (called by tools/run.sh; see its header for the stages).
#   pre     redeploy the arm (same image, same sweep mode) with CHAOS_ENABLED=true; check
#           GET /admin/chaos/status answers 200
#   inject  at T-60 s log in as admin and POST /admin/chaos/halt; the instance that took the request
#           halts the JVM 200 ms later (RuntimeHalter: Runtime.halt, no shutdown hooks)
#   post    redeploy with CHAOS_ENABLED=false; check /admin/chaos/status answers 404
# Writes RUN_DIR/fault-f1.json: the halt request and response times, the HTTP code, and the instance
# name the endpoint returned (the Cloud Run revision, K_REVISION).
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_stage

HALT_BEFORE_T_MS=60000
OUT="${RUN_DIR}/fault-f1.json"
BASE="$(arm_url "${ARM}")"

case "${FAULT_STAGE}" in
  pre)
    redeploy_arm "${ARM}" CHAOS_ENABLED=true
    token="$(admin_token "${BASE}")"
    [ -n "${token}" ] || { echo "admin login failed on ${BASE}" >&2; exit 1; }
    code="$(chaos_status "${BASE}" "${token}")"
    [ "${code}" = "200" ] || { echo "chaos endpoint not on after redeploy (GET /admin/chaos/status -> ${code})" >&2; exit 1; }
    json_write "${OUT}" fault f1 chaosEnabledAt "$(iso_ms "$(now_ms)")" chaosStatusBefore "${code}"
    echo "chaos enabled on dd-arm-${ARM}"
    ;;
  inject)
    : "${T_EPOCH_MS:?T_EPOCH_MS missing}"
    target=$(( T_EPOCH_MS - HALT_BEFORE_T_MS ))
    # Log in early: a login at the planned moment would push the halt later.
    sleep_until_ms $(( target - 30000 ))
    token="$(admin_token "${BASE}")"
    [ -n "${token}" ] || { echo "admin login failed on ${BASE}" >&2; exit 1; }
    sleep_until_ms "${target}"
    body_file="$(mktemp)"
    sent_ms="$(now_ms)"
    # --data '' sends Content-Length: 0; a bodiless POST without it gets 411 from Google's front end
    # (day 17, first A f1 attempt).
    code="$(curl -s -o "${body_file}" -w '%{http_code}' --max-time 30 -X POST --data '' \
      -H "Authorization: Bearer ${token}" "${BASE}/admin/chaos/halt" || true)"
    answered_ms="$(now_ms)"
    # HaltResponse {"halting": "<K_REVISION>"}
    instance="$(grep -o '"halting":"[^"]*"' "${body_file}" | head -n 1 | cut -d'"' -f4 || true)"
    echo "POST /admin/chaos/halt -> ${code}: $(cat "${body_file}")"
    rm -f "${body_file}"
    json_write "${OUT}" plannedAt "$(iso_ms "${target}")" haltSentAt "$(iso_ms "${sent_ms}")" \
      haltAnsweredAt "$(iso_ms "${answered_ms}")" haltSentMinusTMs "$(( sent_ms - T_EPOCH_MS ))" \
      haltHttpCode "${code}" instance "${instance:-null}"
    [ "${code}" = "202" ] || { echo "halt was not accepted (HTTP ${code})" >&2; exit 1; }
    ;;
  post)
    redeploy_arm "${ARM}" CHAOS_ENABLED=false
    token="$(admin_token "${BASE}")"
    code="$(chaos_status "${BASE}" "${token}")"
    json_write "${OUT}" chaosDisabledAt "$(iso_ms "$(now_ms)")" chaosStatusAfter "${code}"
    [ "${code}" = "404" ] || { echo "chaos endpoint still answers ${code} after redeploy" >&2; exit 1; }
    echo "chaos disabled on dd-arm-${ARM}"
    ;;
esac
