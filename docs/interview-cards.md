# Interview cards

One card per question. Each has the strong answer with the real number and its source, and the
follow-up that answer invites (with a short answer to that too). Numbers come from `results/`; the
file is named on each card.

The review's questions (Q1-Q5) and the proposal's five hardest questions were not saved in the
repository, so these ten are reconstructed from what the review and proposal asked about
(fairness, cheating, the clock, the size of the effect, database outages, ground truth, cold
starts, races, the queue and scale). Re-title a card if the wording you were given differs.

---

## Review Q1. Why not just give everyone a longer grace period?

**Answer.** Grace without a commitment lets anyone keep working after the deadline: a student who
finishes at 12:04 and uploads inside a 5-minute grace looks the same as one who clicked at 11:58 on
slow Wi-Fi. The hash commitment separates them. The stamp is taken when the commit (hash, size,
type) arrives, and grace only admits bytes whose SHA-256 matches what was committed before the
deadline. Arm B, which is a direct upload with a stamp at finalize, misjudged 1.50% of on-time
students in the 1,000-student cohort; arm C misjudged 0 (`results/headline.md`).

**Follow-up it invites:** "How long should the grace be?" Long enough for the slowest honest upload
to finish. In the experiment it was 300 s, and in F2 the reconciler finished abandoned uploads at a
p50 of 152.4 s and a max of 172.4 s, inside it (`results/failure/SUMMARY.md`).

---

## Review Q2. Can a student commit early and upload something else later?

**Answer.** No. The commit binds the stamp to a SHA-256. After upload, a job hashes the stored
object; if it differs the submission becomes REJECTED_HASH_MISMATCH, never VERIFIED. The signed URL
also pins the object name, content type and exact size range, and the real bucket refuses a
changed type or name with 403 and an oversize PUT with 400 (`results/smoke-bucket.log`). To commit
a hash before the deadline you need the finished file before the deadline, which is exactly the
rule.

**Follow-up it invites:** "Couldn't they commit a hash of a file they finish later?" Only by finding
a SHA-256 collision, which is not practical. A student can commit an unfinished file, but then
that unfinished file is what gets graded.

---

## Review Q3. The browser could lie about the time. Why trust it?

**Answer.** It does not send a time. The authoritative stamp, `committed_at`, is read from the
server's injected `Clock` in `CommitTimestampFilter`, at the highest filter precedence: before
security, body parsing or the database. The browser only syncs a countdown for display. The
database's own `now()` is kept as `db_committed_at` for audit and never decides anything. Arm C
recorded 0 truly late students as on time across all its counted runs (`results/headline.md`).

**Follow-up it invites:** "Why not the database clock?" It is a second machine with its own skew,
and it is only read after the request waits for a pooled connection (pool of 5, 2 s timeout).
Using it would make fairness depend on the slowest component, which is arm B's mistake one step
earlier.

---

## Review Q4. 1.50% is small. Is this a real problem?

**Answer.** It is under 5%, so I followed the rule I wrote on day 1 before any data: lead with the
count per course and the ablation, and never widen the cohort to make the number bigger. That
gives about 5 on-time students misjudged per 1,000-student course by arm B (4.8, model-scaled),
against 0 for arm C, with the same code, schedule and network (`results/headline.md`). Each of
those is a real student marked late for their Wi-Fi. And the model predicted it before the first
counted run (B 1.32% at 1x), measured landed +0.18 points from the model.

**Follow-up it invites:** "So your day-1 prediction was wrong?" Yes. I predicted 15-35% for arm A
before I had public network data; once the Ookla profiles were in, the model said about 1.3%, and
the measurement agreed with the model, not with me. That is in `PREDICTIONS.md`.

---

## Review Q5. What happens if the database goes down at 11:59?

**Answer.** I tested a Cloud SQL restart 90 s before the deadline (F3). It lasted 10-21 s. Arm C
misjudged 0 of 429 truly on-time students with the signed receipt (11 commits got a receipt
instead of a 503) and also 0 of 429 without it: 37 commits got 503 and retried in time
(`results/failure/SUMMARY.md`). My pre-registered prediction said that without the receipt at
least one student would be misjudged. That was wrong at this outage length, and I report it
against myself. I do not claim anything about an outage that reaches the deadline; it was not
tested.

**Follow-up it invites:** "So is the receipt useless?" Its measured effect here is 11 commits
accepted on the first try. It matters for an outage that lasts past the deadline, which I designed
for but did not measure. It also has a known hole: if the browser dies after uploading while the
database is still down, the submission is lost (D12); recovery by listing the bucket is designed,
not built.

---

## Proposal hardest 1. How do you know who was "truly" on time? (the 5-second margin)

**Answer.** Ground truth is the click time recorded by k6 on NTP-synced load VMs, not anything the
system under test says. A student is truly on time only if they clicked at least 5 s before the
deadline. The margin covers the things that can legitimately move a click across the line: clock
skew between the VMs and the server (a run aborts if skew is over 100 ms), browser hashing time
and one round trip. The model's worst case for hash plus RTT is 0.35 s, well inside 5 s
(`PREDICTIONS.md` addendum 2). Clicks in the last 5 s are not scored either way; they are reported
apart. In the first 1,000-student run there were 32 of them; arm C recorded 31 on time and 1 late,
arm B 17 and 15 (`results/runs/*_1000_r1/confusion.json`).

**Follow-up it invites:** "Doesn't the margin hide errors?" It can only hide errors in the last 5 s.
Every misjudgement in the headline is a student who clicked at least 5 s early, so the margin makes
the headline conservative, not flattering.

---

## Proposal hardest 2. What about Cloud Run cold starts? (the cold-start answer)

**Answer.** A cold start of arm C took a median of 14842.6 ms over 5 samples
(`results/coldstart.json`). In the cold-rush test, arm C sat at zero instances for 19.6 minutes; the
first request then took 17007.1 ms, and the 500-student rush after it misjudged 0 of 429 with a
commit p95 of 213.4 ms. Honest caveat: the cold start was paid by the logins before the first click,
so this measured scale-out from zero, not a click landing on a cold service. A student who logs in
less than about 15 s before clicking would meet it, and their stamp would be late by that much.

**Follow-up it invites:** "How would you fix that in production?" Keep one minimum instance during
the hours around a deadline. It costs a little idle Cloud Run time and removes the only cold start
that can touch a stamp.

---

## Proposal hardest 3. Two things finish the same upload at once. How do you not double-count?

**Answer.** Every state change is one statement, `UPDATE ... WHERE id = ? AND status = <expected>`,
and the caller checks rows affected; only the winner writes the event and enqueues the job, in the
same transaction. A unique idempotency key per student makes a retried commit replay, and a partial
unique index allows one live submission per student per assignment. Six race test classes cover it
(`docs/tests.md`); `FinalizeVsReconcilerRaceIT` shows exactly one UPLOADED event and one job, and
`GuardRemovedRaceIT` shows that removing the `AND status = ?` guard produces two. Across all counted
runs: 0 lost, 0 duplicates (`results/SUMMARY.md`).

**Follow-up it invites:** "Why not `SELECT ... FOR UPDATE` or `@Version`?" `FOR UPDATE` holds a lock
and a pooled connection across a round trip to the app with a pool of 5; `@Version` turns the
expected case (one of the two callers should just be a no-op) into an exception and a retry loop.

---

## Proposal hardest 4. Why a job table in Postgres, not Kafka, Redis or `@Scheduled`?

**Answer.** It is a transactional job queue: the job row is inserted in the same transaction as the
transition, so a job exists if and only if the transition committed (`JobEnqueueAtomicityIT`). Jobs
are claimed with `FOR UPDATE SKIP LOCKED`, so drainers never collide. Cloud Scheduler drains it over
HTTP with OIDC every minute; `@Scheduled` would run once per instance and stop when Cloud Run scales
to zero. Kafka or Redis would add a second system and a second copy of state to the deadline path,
and nothing measured needed them: the hot queries all run well under a millisecond on their indexes
(`results/explain.md`).

**Follow-up it invites:** "What is the cost of polling every minute?" Latency: a VERIFY waits up to
about a minute, and abandoned uploads in F2 were recovered at a p50 of 152.4 s because the
reconciler waits 2 minutes before touching a row. Fairness is unaffected: the stamp was taken at
commit.

---

## Proposal hardest 5. What breaks at 10x, and what does it cost?

**Answer.** Measured, not guessed: signing is the largest part of every arm C commit, 73.0 ms of
101.0 ms app time at 1,000 students, against 26.0 ms for the database
(`results/runs/20261121T200324Z_C_1000_r1/confusion.json`). Every signature is per student and per
object, so a cache cannot help; at 10x it is the first thing to measure. Second is the connection
budget across instances. For the baseline, more servers do not help at all: arm A at 10 instances
misjudged 2.19%, the same as at 3 instances (`results/headline.md`). Cost: the whole project used
₹3,121.37 of cloud, ₹131.98 per 1,000 submissions, and that is an upper bound because it includes
development, idle Cloud SQL and the load VMs (`results/billing/cost-per-1000.md`).

**Follow-up it invites:** "Why did the 10-instance run not improve arm A?" Because its error is the
upload time of slow students, which finishes after the deadline whatever the server does. That is
the point of the project: the fix is where the stamp is taken, not how much capacity you buy.
