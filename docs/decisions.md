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
No student pilot: network profiles come from published Indian speed data; click-time curve is a stated assumption tested at 0.5x and 2x (approved change to review gap G4).
Profiles and their sources: `docs/network-profiles.md`.

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

## D11: Signed-URL Edge-Case Enforcement (phase 26, 2026-11-17)
The real bucket enforces the signed `x-goog-content-length-range` itself: `results/smoke-bucket.log` shows a 2 MiB PUT against a 1 MiB signature rejected with 400 EntityTooLarge, a PUT with a different Content-Type and a PUT to a different object name both rejected with 403 SignatureDoesNotMatch, so size is enforced by GCS at PUT time and no oversize object is stored. The local storage server mirrors these rejections for `SignedUrlSecurityIT`, and `FinalizeService` still compares the stored size with the committed size (422 SIZE_MISMATCH, object deleted) as a second line, but that path was not exercised against GCS because GCS never accepted the oversize bytes.

## D12: Signed commit receipt, and the limit that remains (phase 28, 2026-11-17)
Gate G12 passed: tag `flow-live` exists (26eed36) and CI succeeded on the last commit dated 14 Nov (daee327, run 43619108454), so the receipt was built.
- **What it does.** If the commit's database block fails because the database cannot be reached (pool timeout, resource failure, or a JDBC class-08 connection error in the cause chain: the same cases that used to give 503) and `RECEIPT_ENABLED` is true (default), the server still answers 201: status `RECEIPT_ISSUED`, timing `PENDING`, `committedAt` = the arrival stamp, a signed upload URL, and `receipt` = base64url(payload) + "." + base64url(HMAC-SHA256(`RECEIPT_HMAC_KEY`, payload)). The browser uploads as usual and sends the receipt in the finalize body. Finalize verifies it, checks enrolment and the assignment limits that the commit could not check, refuses it after max(deadline, committedAt) + grace (410 `RECEIPT_STALE`), stats the object, and in one transaction inserts the row with `committed_at` from the receipt (`receipt_nonce` unique, so a second redeem replays) and moves it to UPLOADED. With `RECEIPT_ENABLED=false` the commit keeps today's 503.
- **The limitation that remains.** If the browser dies after uploading while the database is still down, the object exists in the bucket with no row. The reconciler works from rows (`COMMITTED` older than the threshold), so it cannot see the object, and the receipt that would prove the stamp was only ever in the dead browser. That submission is lost. Recovery would need a bucket listing: walk `a/{assignmentId}/s/{studentId}/{submissionId}` objects with no row, read the stamp from signed object metadata written at commit, and create the row. Designed, not built.
- **Other edges, not built.** A receipt is not tied to the commit's Idempotency-Key, so a commit retried during the outage gets a second receipt (the browser keeps the last one). (Fixed 2026-11-17: redeeming a receipt no longer supersedes a live submission committed after the receipt's stamp. The newest commit wins; the receipt's submission is recorded as SUPERSEDED by it.)

## D14: Still no cache (phase 29, 2026-11-18)
There is no cache in the application, on purpose. Every hot read is a primary-key or indexed lookup: `results/explain.md` (1,000 students, 1,000 submissions, mixed states) shows the replay lookup, the transition, the reconciler candidates, the job claim and the student's own lists each finishing in well under 1 ms of execution time, each served by a named index (`ux_sub_student_key`, `submissions_pkey`, `ix_sub_reconcile`, `ix_jobs_ready`, `ix_enroll_student`). The one query above 1 ms is the teacher dashboard (2.8 ms for 1,000 rows), polled every 5 s by one teacher, with an ETag so an unchanged board costs a 304. No measurement in `results/` shows a read-path bottleneck; the first one the runs found is URL signing in the commit (D10), which a cache cannot help because every signature is per object and per student.
A cache would also cost something the project cannot afford: a second copy of submission state that can disagree with the row the status guard protects. The fairness argument rests on `UPDATE ... WHERE id = ? AND status = ?` being the only truth.
**Where one would go at 10x** (10,000 students, many concurrent dashboards): in process, inside `DashboardService`, keyed by assignment id, holding the computed `DashboardResult` for 2 s (a Caffeine-style bounded map with expire-after-write; that library would need approval, a `ConcurrentHashMap` with a timestamp works until then). The 5-second ETag poll already tolerates 2 s of staleness, and it turns N teachers polling into one query per assignment per 2 s per instance. The second candidate is `AssignmentRepository` reads for the student list, keyed by course id and invalidated by the assignment PATCH (version bump). Commit, finalize, transition and job queries are never cached.

## D13: Dependency and secret scan (phase 29, 2026-11-18)
- **gitleaks** (`detect --log-opts="--all"`, 69 commits): 1,301 raw hits. 1,300 were `jwt` matches in `results/runs/*_dryrun/summary-*.json`: the k6 dry runs log the load-test students' access tokens (seeded accounts, 12-hour lifetime, all expired 2026-11-14). Counted and dry-run evidence is never edited, so `.gitleaks.toml` allowlists the paths `results/runs/` and `results/explain.md` (a path allowlist, because the older gitleaks in the CI action cannot target one rule). The new test token strings in `src/test/` fall under the existing test-sources allowlist. One hit was a dummy `JWT_SECRET` ("storage-dummy-jwt...") in `application-storage.yml` at commit 09e4983, removed in 01b7264; fingerprinted in `.gitleaksignore`. Result after that: no leaks. Rule for the future: k6 summaries must not log tokens in the measured runs.
- **OSV-Scanner** on `pom.xml` (direct and transitive): 21 findings in 8 packages (4 Critical, 12 High, 5 Medium) on Spring Boot 4.1.1's BOM and libraries-bom 26.54.0. Worst: Tomcat 11.0.24 (four, CVSS 9.8, 9.8, 9.1, 9.1; fixed in 11.0.25), Jackson 2.21.5 and 3.1.5 (jackson-core and databind, up to 7.5; fixed in 2.21.7 / 3.1.7), Guava 33.4.0 (8.2), grpc-netty-shaded 1.69.0 (8.2), OpenTelemetry API 1.62.0 (5.3). All upgraded rather than accepted: `tomcat.version`, `jackson-bom.version`, `jackson-2-bom.version`, `opentelemetry.version` overridden in `pom.xml`, and `libraries-bom` 26.54.0 to 26.94.0 for Guava and grpc. Rescan: no issues. Nothing is accepted, so `osv-scanner.toml` is empty; CI job `dependency-scan` fails on any new finding.
- **Trade-off:** pinning patch versions above the Boot BOM means they must be removed when Boot ships the same or newer ones; the comment in `pom.xml` marks them.

- **Known limit (phase 36):** after a database restart, the first request on a stale pooled connection may get a 503 that the browser retries.

## D16: Shutdown (phase 40, 2026-11-25)
- **No pilot hand-over.** There was no pilot (D8), so there was no pilot assignment to download or hand over and no student accounts to delete. `telemetry_events` and `survey_responses` had 0 rows in dd_a, dd_b and dd_c, so there was no aggregate telemetry to export; secret `dd-telemetry-salt` was destroyed with the other secrets.
- **Kept:** `results/cloud-inventory/` (describe JSON of every deleted resource, secrets redacted, taken before any delete) and `results/backup/` (`pg_dump -Fc` of the three arm databases, under 5 MB, tracked in git; `gcloud storage ls -r` of the bucket).
- **Deleted on 2026-11-25 between 15:20 and 15:52 UTC (20:50-21:22 IST), with `tools/shutdown.sh`, one typed confirmation per group, in this order:** Cloud Scheduler jobs dd-drain-c and dd-reconcile-c; Cloud Run services dd-arm-a/b/c and jobs dd-seed-a/b/c; Cloud SQL instance dd-pg; all 1,210 objects and the bucket (invalid-run leftovers and the Lab 4 test files included); Artifact Registry repository dd; secrets dd-*; service accounts dd-run, dd-deployer, dd-scheduler; Workload Identity pool github. No dd-* VMs existed. The Deploy workflow was disabled before the first push of the phase. Budget alert dd-40 is kept. Listings proving it: `results/cloud-inventory/after-shutdown.txt`.
- **Not deleted:** the custom IAM role `ddBucketCorsUpdater` (not billable, not in the shutdown list) and the project itself.
