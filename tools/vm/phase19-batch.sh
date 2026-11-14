#!/usr/bin/env bash
# Phase 19 steps 5b-5e, run unattended from the operator's machine (or, parked until phase 32, from the
# controller VM via tools/vm/controller.sh start):
#   b. sanity from each VM          c. 100-student dry runs a, b, c (stop if arm C exits 3)
#   d. 1,000-student arm C dry run with free -m sampled on each VM every 5 s
#   e. shaping cleared, then tools/coldstart.sh
# Everything lands in this checkout's results/; progress goes to results/remote/progress.log.
# The first failing step stops the batch and writes STOPPED to the log; DONE marks success.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/common.sh"
# deploy/env.sh (sourced by common.sh) turns on errexit; this batch checks every exit code itself
# and must log END/STOPPED, so switch it back off. Without this the first failing run.sh killed the
# batch silently (twice in phase 19).
set +e

LOG_DIR="${REPO_ROOT}/results/remote"
LOG="${LOG_DIR}/progress.log"
MEM_DIR="${REPO_ROOT}/results/dryrun-1000"
mkdir -p "${LOG_DIR}" "${MEM_DIR}"
export SCHEDULE_MODE=placeholder
PY="$(find_python)"

say() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" | tee -a "${LOG}"; }
stop() { say "STOPPED: $*"; exit 1; }
# step <name> <command...>: full output to results/remote/<name>.out, one line per step in the log
step() {
  local name="$1"; shift
  say "START ${name}: $*"
  "$@" > "${LOG_DIR}/${name}.out" 2>&1
  local rc=$?
  say "END ${name}: exit ${rc}"
  return "${rc}"
}

say "batch started on $(hostname), commit $(git -C "${REPO_ROOT}" rev-parse --short HEAD)"

# One SSH connection per VM, one at a time, before anything runs in parallel: the first
# gcloud compute ssh creates ~/.ssh/google_compute_engine, and parallel first calls race on it.
for c in "${COHORTS[@]}"; do
  vm_ssh "$(vm_name "${c}")" "true" >/dev/null 2>&1 || stop "ssh to $(vm_name "${c}") failed"
done
say "ssh to all VMs ok"

# START_AT=sanity|a|b|c|1000|coldstart resumes a stopped batch at that step (default: sanity).
ORDER=(sanity a b c 1000 coldstart)
START_AT="${START_AT:-sanity}"
start_idx=-1
for i in "${!ORDER[@]}"; do [ "${ORDER[$i]}" = "${START_AT}" ] && start_idx=$i; done
[ "${start_idx}" -ge 0 ] || stop "START_AT must be one of: ${ORDER[*]}"
want() { local i; for i in "${!ORDER[@]}"; do [ "${ORDER[$i]}" = "$1" ] && [ "$i" -ge "${start_idx}" ] && return 0; done; return 1; }
[ "${START_AT}" = sanity ] || say "resuming at ${START_AT}"

# ---- b. sanity
if want sanity; then
  step sanity bash "${SCRIPT_DIR}/sanity.sh" || stop "sanity outside limits (results/sanity/*.txt)"
  for c in "${COHORTS[@]}"; do say "  ${c}: $(tail -n 1 "${REPO_ROOT}/results/sanity/${c}.txt")"; done
fi

# ---- c. 100-student dry runs
for arm in a b c; do
  want "${arm}" || continue
  step "run-${arm}-100" bash "${REPO_ROOT}/tools/run.sh" "${arm}" 100 1 none --dryrun
  rc=$?
  if [ "${arm}" = c ] && [ "${rc}" -eq 3 ]; then
    say "arm C misjudged students (G3):"
    grep -A 40 "G3 FAILED" "${LOG_DIR}/run-c-100.out" | tee -a "${LOG}"
    stop "arm C analyze exit 3; not continuing"
  fi
  [ "${rc}" -eq 0 ] || stop "run.sh ${arm} 100 exited ${rc} (results/remote/run-${arm}-100.out)"
  tail -n 6 "${LOG_DIR}/run-${arm}-100.out" | sed 's/^/  /' | tee -a "${LOG}"
done

# ---- d. 1,000-student arm C dry run with memory sampling
if want 1000; then
SAMPLER='nohup bash -c "while :; do echo \"== \$(date -u +%Y-%m-%dT%H:%M:%SZ)\"; free -m; sleep 5; done" > ~/dd/mem.log 2>&1 < /dev/null & echo $! > ~/dd/mem.pid'
for c in "${COHORTS[@]}"; do vm_ssh "$(vm_name "${c}")" "mkdir -p ~/dd; ${SAMPLER}" >/dev/null 2>&1 || stop "memory sampler on ${c}"; done
say "memory samplers started"
step run-c-1000 bash "${REPO_ROOT}/tools/run.sh" c 1000 1 none --dryrun
RC1000=$?
for c in "${COHORTS[@]}"; do
  vm="$(vm_name "${c}")"
  vm_ssh "${vm}" 'kill $(cat ~/dd/mem.pid) 2>/dev/null; true' >/dev/null 2>&1
  vm_scp "${vm}:dd/mem.log" "${MEM_DIR}/mem-${c}.log" >/dev/null 2>&1 || stop "fetching mem.log from ${vm}"
done
"${PY}" - "$(to_native_path "${MEM_DIR}")" "${COHORTS[@]}" > "${MEM_DIR}/vm-memory.txt" <<'PYEOF'
import sys
from pathlib import Path
d, cohorts = Path(sys.argv[1]), sys.argv[2:]
print("# free -m on each k6 VM every 5 s during the 1,000-student arm C dry run (phase 19, step 5d)")
print("# columns: vm, UTC time, total MiB, used MiB, available MiB, used %")
peaks = []
for c in cohorts:
    vm, at, peak = f"dd-k6-{c}", None, (0.0, None)
    for line in (d / f"mem-{c}.log").read_text(encoding="utf-8").splitlines():
        if line.startswith("== "):
            at = line[3:].strip()
        elif line.startswith("Mem:"):
            f = line.split()
            total, used, avail = int(f[1]), int(f[2]), int(f[6])
            pct = 100.0 * (total - avail) / total
            print(f"{vm} {at} {total} {used} {avail} {pct:.1f}")
            if pct > peak[0]:
                peak = (pct, at)
    peaks.append((vm, peak))
for vm, (pct, at) in peaks:
    print(f"PEAK {vm} {pct:.1f}% (total - available) at {at}")
PYEOF
rm -f "${MEM_DIR}"/mem-*.log
tail -n 3 "${MEM_DIR}/vm-memory.txt" | sed 's/^/  /' | tee -a "${LOG}"
tail -n 6 "${LOG_DIR}/run-c-1000.out" | sed 's/^/  /' | tee -a "${LOG}"
say "run-c-1000 exit ${RC1000} (a dry run: reported, not a stop condition)"
fi

# STOP_AFTER=1000 ends here, e.g. to delete the fleet before cold start (coldstart.sh needs no VMs).
if [ "${STOP_AFTER:-}" = 1000 ]; then say "DONE (STOP_AFTER=1000)"; exit 0; fi

# ---- e. cold start, from an unshaped link
for c in "${COHORTS[@]}"; do vm_ssh "$(vm_name "${c}")" "sudo bash ~/dd/shape.sh clear" >/dev/null 2>&1; done
say "shaping cleared on all VMs"
step coldstart bash "${REPO_ROOT}/tools/coldstart.sh" || stop "coldstart.sh failed (results/remote/coldstart.out)"
"${PY}" -c "import json; r=json.load(open(r'$(to_native_path "${REPO_ROOT}/results/coldstart.json")')); print('  coldstart median', r['median'], 'ms;', [s['ms'] for s in r['samples']])" | tee -a "${LOG}"

say "DONE"
