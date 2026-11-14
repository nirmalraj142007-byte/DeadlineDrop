# Phase 32 plan (carried forward)

Steps recorded before phase 32 starts. The phase 32 prompt adds the rest; if it disagrees with this file, ask.

## Step 1 — prove the resized fleet has memory headroom (before any counted run)

1. `bash tools/vm/create-fleet.sh create` (slow and medium on e2-highmem-2, fast on e2-standard-2: 6 vCPU;
   docs/decisions.md D10).
2. Repeat the 1,000-student arm C memory dry run exactly as in phase 19:
   `START_AT=1000 STOP_AFTER=1000 bash tools/vm/phase19-batch.sh` (writes `results/dryrun-1000/vm-memory.txt`
   and a `results/runs/<stamp>_C_1000_r1_dryrun/` folder, `"counted": false`).
3. **Gate:** every VM's `PEAK` line in `vm-memory.txt` must be **under 70%** (total - available). If any VM is at
   or above 70%, stop: no counted run may start until a re-sized fleet passes this step.
4. Keep the laptop awake for the whole run (it entered Modern Standby during phase 19 runs).

## Parked from phase 19

- Controller-VM route (dd-k6-slow driving the batch as service account dd-ctl): set up and torn down in
  phase 19; `tools/vm/controller.sh` remains. Revisit only if laptop-driven runs stay unreliable.
- Cohort shaping values in `tools/vm/cohorts.env` are placeholders (MEDIUM_LOSS lowered to 0.2% in D10);
  phase 32 replaces them from pilot percentiles, then re-runs `tools/vm/sanity.sh`.
