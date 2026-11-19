#!/usr/bin/env bash
# Run a plan of cloud runs in order:
#   bash tools/sweep.sh <plan-file>
# Each plan line is "arm size repeat [fault]" (blank lines and # comments are ignored). Every line
# calls tools/run.sh; the sweep goes on past an invalid run (run.sh exit 4) or an arm C misjudgement
# (exit 3, a valid result) and prints a summary at the end. Output goes to results/sweep-<UTC date>.log.
# run.sh is resumable, so invoking the sweep again with the same plan skips finished runs and repeats
# invalid ones into _rerun folders.
# Sleep is held off for as long as the sweep runs: caffeinate (macOS), systemd-inhibit (Linux) or
# a Windows power request through PowerShell (SetThreadExecutionState).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PLAN="${1:-}"
[ -n "${PLAN}" ] && [ -f "${PLAN}" ] || { echo "Usage: $0 <plan-file>" >&2; exit 1; }

LOG="${REPO_ROOT}/results/sweep-$(date -u +%Y-%m-%d).log"
say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "${LOG}"; }

INHIBIT_PID=""
inhibit_sleep() {
  if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -i -w $$ & INHIBIT_PID=$!
  elif command -v systemd-inhibit >/dev/null 2>&1; then
    systemd-inhibit --what=sleep:idle --why="DeadlineDrop sweep" sleep infinity & INHIBIT_PID=$!
  elif command -v powershell.exe >/dev/null 2>&1 && [ -r "/proc/$$/winpid" ]; then
    local winpid; winpid="$(cat "/proc/$$/winpid")"
    # ES_CONTINUOUS | ES_SYSTEM_REQUIRED, held until the sweep's bash process is gone.
    powershell.exe -NoProfile -NonInteractive -Command "
      Add-Type -Namespace DD -Name Power -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);';
      [void][DD.Power]::SetThreadExecutionState([uint32]'0x80000001');
      while (Get-Process -Id ${winpid} -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 30 }" >/dev/null 2>&1 &
    INHIBIT_PID=$!
  else
    say "WARNING: no way to hold off sleep found (caffeinate, systemd-inhibit, powershell.exe)"
    return 0
  fi
  say "sleep inhibited while the sweep runs (helper pid ${INHIBIT_PID})"
}
cleanup() { [ -n "${INHIBIT_PID}" ] && kill "${INHIBIT_PID}" 2>/dev/null; }
trap cleanup EXIT

say "sweep started: plan ${PLAN}, commit $(git -C "${REPO_ROOT}" rev-parse --short HEAD), host $(hostname)"
inhibit_sleep

SUMMARY=()
n_ok=0; n_skip=0; n_g3=0; n_invalid=0; n_other=0
while read -r arm size repeat fault _rest; do
  case "${arm}" in ''|'#'*) continue ;; esac
  fault="${fault:-none}"
  label="${arm} ${size} r${repeat}$([ "${fault}" != none ] && echo " ${fault}")"
  say "START ${label}"
  out="$(mktemp)"
  bash "${SCRIPT_DIR}/run.sh" "${arm}" "${size}" "${repeat}" "${fault}" < /dev/null 2>&1 | tee "${out}" >> "${LOG}"
  rc=${PIPESTATUS[0]}
  folder="$(grep -o 'Run folder: .*' "${out}" | tail -n 1 | sed 's|.*/||' | tr -d '\r')"
  if grep -q '^SKIP ' "${out}"; then
    result="skipped ($(grep '^SKIP ' "${out}" | sed 's/.*already done in //' | tr -d '\r'))"; n_skip=$((n_skip + 1))
  else
    case "${rc}" in
      0) result="ok"; n_ok=$((n_ok + 1)) ;;
      3) result="VALID, arm C misjudged students (G3 failed)"; n_g3=$((n_g3 + 1)) ;;
      4) result="INVALID: $(grep -o 'RUN INVALID: .*' "${out}" | tail -n 1 | sed 's/^RUN INVALID: //; s/ (.*$//' | tr -d '\r')"; n_invalid=$((n_invalid + 1)) ;;
      *) result="exit ${rc}"; n_other=$((n_other + 1)) ;;
    esac
    score="$(grep -E '^  onTime_onTime ' "${out}" | tail -n 1 | sed 's/^ *//' | tr -d '\r')"
    [ -n "${score}" ] && result="${result}; ${score}"
  fi
  rm -f "${out}"
  say "END ${label}: exit ${rc}, ${folder:-no folder}, ${result}"
  SUMMARY+=("${label} | exit ${rc} | ${folder:-no folder} | ${result}")
done < <(tr -d '\r' < "${PLAN}")

say "SUMMARY (${#SUMMARY[@]} lines: ${n_ok} ok, ${n_skip} skipped, ${n_g3} G3 failed, ${n_invalid} invalid, ${n_other} other)"
for line in "${SUMMARY[@]}"; do say "  ${line}"; done
[ "${n_invalid}" -gt 0 ] && say "Invalid runs are repeated into _rerun folders by running: bash tools/sweep.sh ${PLAN}"
say "sweep finished"
[ $((n_invalid + n_other)) -eq 0 ]
