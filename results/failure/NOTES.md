## What the table shows

**Arm C never lost, duplicated or misjudged a student in any failure test** (F1, F2, F3 with and
without the receipt, and the cold rush): 0 of 429 truly on-time students recorded late or missing,
0 late-to-on-time, 0 lost, 0 duplicates in all six arm C rows. No arm C fix or re-run was needed.
Every arm ran the same 500-student schedule (seed of size 500, repeat 1), so the rows compare with
each other and with the no-fault day-16 runs of that schedule (A and B: 9 of 429 misjudged there too).

**F1, instance crash at T-60 s.** Each halt was accepted (HTTP 202) about 59 s before the deadline
and stopped one instance with `Runtime.halt` (no shutdown hooks). A and B kept their no-fault result
(9 of 429, the same slow-cohort students); the requests on the dead instance (4-5 per arm) were
retried. Cost to arm C: commit p95 10.8 s against 0.2-0.4 s without a fault, while commits queued for
the replacement instance during the final-minute peak. Nobody was misjudged because a commit is
stamped when it reaches the app and every queued on-time commit still arrived before the deadline; a
crash in the last ~15 s (one cold start, results/coldstart.json median 14.8 s) could push an on-time
click past T, which this test did not cover. The endpoint returns the Cloud Run revision
(`K_REVISION`), not a per-instance id, so the "instance" column names the revision that held the
halted instance.

**F1 attempts that did not count.** Arm A's F1 took three runs. Attempt 1: `f1.sh` sent the halt
POST without a body, Google's front end answered 411, nothing was halted; the sweep was stopped and
the folder marked invalid with that reason. Attempt 2: the single halt request hit a Cloud Run
front-end 503 at the rush peak (Cloud Logging: 3 such 503s on arm A in 15 min, the halt and two
uploads); `run.sh` marked it invalid because the fault was not injected. `f1.sh` now sends
`--data ''` and retries the halt up to 5 times, recording every attempt (`haltAttempts`); `_rerun2`
landed on the first try. Both invalid folders are kept.

**F2, browser dies after upload.** 47 of 500 students (40 of them truly on time) uploaded and never
called finalize. Arm B has no row for any of them (stamp at finalize), so its on-time misjudged count
rises from 9 to 48 (11.19%). Arm C recorded all 47 as VERIFIED: the reconciler (minimum age 2 min,
Cloud Scheduler every minute) moved each COMMITTED row whose object existed to UPLOADED 152 s after
the upload ended (p50; max 172 s), well inside the 300 s grace, and the stamp is the commit's.

**F3, Cloud SQL restart at T-90 s.** On this instance a restart is short: the restart operations
(`gcloud sql operations describe`, read after each run) took 10-21 s, and the arms' health checks
failed for 9.5-10.4 s:

| Run | Operation | Start | End |
|---|---|---|---|
| A f3 | b0af33b6-3d11-4610-9ce3-90430000002f | 18:21:48.6Z | 18:22:09.1Z |
| B f3 | f4a9f743-0406-4cd6-bac7-6d770000002f | 18:46:45.0Z | 18:46:55.4Z |
| C f3 | 42e09bb4-97fb-45f0-896f-0c840000002f | 19:11:28.4Z | 19:11:38.8Z |
| C f3-noreceipt | b28dd1a4-1b02-4770-99e9-52c50000002f | 19:37:02.8Z | 19:37:23.4Z |

`fault-f3.json` has `operationEndTime: null` in these four runs: `f3.sh` stopped polling at the first
healthy check after a failure, 9-20 s before the operation ended. Arm A's Cloud Logging shows 503s
only between 18:21:48 and 18:21:59 and none after, so there was no second outage. `f3.sh` now polls
until the operation is DONE.

**F3 with and without the receipt (reported against ourselves).** With receipts on, 11 commits that
arrived while the database was unreachable got a signed receipt instead of a 503; all 11 were truly on
time and ended VERIFIED and on time (the export's `stampSource` column says COMMIT for every arm C row,
receipt rows included, because the receipt carries the commit's arrival stamp). With
`RECEIPT_ENABLED=false`, 37 commits got 503 and were retried after the database came back; all were
still stamped before the deadline, so arm C misjudged 0. PREDICTIONS.md item 7 ("F3 without a signed
receipt: more than 0 students misjudged") is **not borne out** by this run: a 10-21 s outage starting
90 s before the deadline leaves over a minute for retries. The receipt's measured effect here is 11
commits accepted on the first try; a misjudgement without it needs an outage that reaches the
deadline, which this restart did not.

**Cold rush.** Arm C at min-instances 0, Cloud Scheduler jobs paused, idle for 19.6 min (time since
the last non-zero `instance_count` point; Cloud Logging showed no request to arm C in that window).
The first request after the idle period took 17.0 s (a cold start). Then `run.sh c 500 1 coldrush`:
0 of 429 misjudged, 0 errors, commit p95 213 ms. That first-instance cold start is paid before the
first click: run.sh's preflight, the assignment creation and k6's logins all reach arm C minutes
earlier, so the rush starts from one live instance and measures scale-out with no warm minimum, not a
click landing on a cold service. In this harness the cold start did not reach the students. A real
course whose students log in less than ~15 s before clicking would see it.
The first cold-rush attempt (`results/coldrush-2026-11-22-attempt1.log`) was stopped before any run:
Monitoring stops writing zero points a few minutes after a revision reaches zero, so counting zero
points froze at 360 s; `tools/coldrush.sh` now measures from the last non-zero point.
