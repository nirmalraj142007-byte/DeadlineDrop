# Architectural and Project Decisions

## D1: Deployment Region and Project (2026-11-06)
Project ID `deadlinedrop-nirmal` in region `asia-south1` (Mumbai) is selected for all cloud infrastructure: Cloud Run, Cloud SQL, the Google Cloud Storage bucket, and k6 load-testing VMs.

## D2: Scope Cuts and Replacements (2026-11-06)
Features cut on day 1 to protect the core deadline guarantee and evaluation timeline, along with their replacements:
- **Cross-student duplicate detection**: Moved to stretch goal.
- **CSV bulk import**: Replaced by automated database seed script.
- **Downloadable receipt file**: Replaced by web-based receipt page (retained).
- **2,000-student sweep point**: Cut to keep within free-tier compute and connection budgets.
- **OpenAPI example payloads**: Replaced by standard generated API documentation (retained).
- **Teacher CSV export**: Replaced by per-file submission inspection and download-all archive endpoints.

## D3: Working Assumptions (2026-11-06)
- Three resume impact bullets drafted upfront to guide benchmark targets.
- Export endpoint is API-only (no UI button).
- Predictions and accuracy metrics are defined strictly in cohort terms.
- Deadline countdown race simulation begins at 0:30 in the demo.
- Final project review scheduled after 27 Nov 2026, so the live demo runs locally.
- 5-minute experiment grace period configured for overnight parameter sweeps.
- Submissions committed after the official deadline timestamp are accepted and recorded as LATE.
- JWT access tokens have a 12-hour validity window.

## D4: Pilot Request and Gate (2026-11-06)
Pilot request sent today (Fri 6 Nov 2026). Decision gate is set for day 5 (Tue 10 Nov 2026): written confirmation from a course instructor/club lead, or fallback to a scheduled mock session with 30+ participants on day 13 or 14.

## D5: Claude Code Usage Windows (2026-11-06)
To maximize throughput and review quality within rate windows: generate implementations in the first window of each day; inspect, read code, and run tests in the interval gaps.

## D6: Credit Balance on Day 1 (2026-11-06)
Credit balance: 
The project budget cap is approximately US$40. Because billing account `011506-D31873-0CFA73` operates in `INR` (Indian Rupee), the budget alert `dd-40` is expressed in INR, and the exact amount to use is `3500INR`.

## D7: Cloud Run Live URLs (2026-11-11)
Deployed Cloud Run service URLs for initial ablation arms in `asia-south1`:
- **Arm A (`naive`)**: `https://dd-arm-a-aheo6nqyga-el.a.run.app`
- **Arm C (real design)**: `https://dd-arm-c-aheo6nqyga-el.a.run.app`


## D8: Pilot Decision (day 5 gate, Tue 10 Nov 2026)
Outcome of the D4 gate. Nirmal fills in exactly one line and deletes the other:
- Confirmed by <name>, date <dd Nov>, approx N <n>, file types <..>
- No confirmation: mock deadline scheduled for <day 13 or 14> with 30+ people

## D10: First cloud runs (phase 19, 2026-11-14)
- **Medium cohort loss 0.5% -> 0.2% (PLACEHOLDER; phase 32 replaces every cohort value from pilot percentiles).**
  The first sanity check measured medium at 3.363 Mbit/s against 4 Mbit/s (-15.9%, limit ±10%) with RTT
  61.3 ms (results/sanity/medium.txt in commit bb4298e). With 0.5% random loss at about 61 ms RTT the TCP
  loss bound (Mathis, MSS 1460) is about 3.29 Mbit/s, so the profile asked for a rate TCP cannot reach.
  At 0.2% the bound is about 5.2 Mbit/s, so the 4 Mbit/s cap is what limits the link again. Slow
  (1%, 80 ms, bound 1.76 Mbit/s > 1 Mbit/s) and fast (0%) were within limits and are unchanged.
- **What the first runs showed (all dry runs on PLACEHOLDER inputs, `"counted": false`):**
  1. Same 100-student schedule: arms A and B each recorded 13 of 85 truly on-time students LATE (rate 0.153; `results/runs/*_A_100_r1_dryrun`, `*_B_100_r1_dryrun`); arm C recorded 0 (85/85 on time, 10/10 late; `*_C_100_r1_dryrun`), so G3 held.
  2. 1,000 students on arm C: 868/868 on time, 100/100 late, 0 lost, 0 duplicates, 0 connection errors; 3 late students were still COMMITTED at export (upload done, k6 gave up finalize at T + grace; reconcile had not run yet) — check reconcile timing before F2 (`*_C_1000_r1_dryrun/confusion.json`).
  3. **First-bottleneck candidate: URL signing.** Arm C commit Server-Timing medians: app 128 ms, sign 99, db 24 (100 students); app 104, sign 77, db 24 (1,000). Signing (IAM signBlob) is the largest phase of the commit.
  4. k6 VM memory peaked at 86.9% (slow), 75.4% (medium), 53.7% (fast) during the 1,000-student run (`results/dryrun-1000/vm-memory.txt`): the slow VM has little headroom for larger runs.
  5. Arm C cold start (GET /api/time after 120 s at zero instances, laptop curl): median 14,842.6 ms over 5 cold samples (`results/coldstart.json`). Harness faults fixed along the way (seed password piped to plink, 60 s k6 timeouts, shaped-link preflight skew, nohup holding SSH, laptop standby) are in the phase-19 ownership-log entry; failed attempts are kept in `results/aborted/`.
- **k6 VM sizes per cohort (phase 19 fix, 2026-11-14).** The 1,000-student dry run peaked at 86.9% memory on
  dd-k6-slow and 75.4% on dd-k6-medium (8 GB e2-standard-2; `results/dryrun-1000/vm-memory.txt`), above the
  70% limit; dd-k6-fast peaked at 53.7%. Slow and medium VUs hold their file buffers longest because their
  uploads take longest, so `tools/vm/common.sh` now gives slow and medium e2-highmem-2 (2 vCPU, 16 GB) and keeps
  fast on e2-standard-2 (2 vCPU, 8 GB). Total stays 6 vCPU under the 8-vCPU cap; `create-fleet.sh` sums
  guestCpus per cohort's type before creating anything and refuses an existing VM of the wrong type. Not yet
  verified: the first step of phase 32 (`docs/phase32-plan.md`) repeats the 1,000-student memory dry run.
