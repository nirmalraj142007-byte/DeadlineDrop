# Tests

Counts are test methods (`@Test`, `@ParameterizedTest`, `@RepeatedTest`) found in `src/test/java` on 2026-11-24;
a parameterized method runs more than once, so the runner's totals are higher.

| Kind | Classes | Methods | Command |
| --- | --- | --- | --- |
| Unit (`*Test`, surefire) | 7 | 44 | `./mvnw test` |
| Integration (`*IT`, failsafe; Testcontainers Postgres, real local storage adapter) | 29 | 208 | `./mvnw verify` (runs the unit tests too) |
| Race / concurrency (`race/*IT`, failsafe) | 6 | 12 | `./mvnw verify -Dit.test='com.deadlinedrop.race.*IT'` |
| End-to-end (k6 against the real compose stack) | 2 scripts | 2 | `make e2e` |

`./mvnw verify` runs every unit, integration and race test. On this Windows machine, if it fails with
"Unable to establish loopback connection", see the Local environment note in `AGENTS.md`.

## Race tests

One line each, on what it proves:

- `DuplicateRetryIT`: 20 simultaneous copies of one commit (same Idempotency-Key) create exactly one submission; the other 19 replay it and none fails.
- `ExpiryAtGraceEndIT`: a commit whose bytes never came is COMMITTED just before grace ends and EXPIRED just after, and late bytes cannot bring it back (finalize answers EXPIRED, never UPLOADED).
- `FinalizeVsReconcilerRaceIT`: finalize and the reconciler racing on COMMITTED -> UPLOADED produce exactly one UPLOADED event and one VERIFY job, and both callers see UPLOADED.
- `GuardRemovedRaceIT`: with the `AND status = ?` guard removed from the transition, the same race writes two UPLOADED events; proves the race test can fail, so it must never be weakened.
- `JobEnqueueAtomicityIT`: if enqueueing the VERIFY job fails, the UPLOADED transition and its event roll back with it, so a VERIFY job exists if and only if the transition committed.
- `SupersedeOnResubmitIT`: a new commit supersedes the old live submission and links to it, and two new commits at the same moment never leave two live rows (the partial unique index refuses it).

## End-to-end tests

Both drive the running stack over HTTP with k6 (version pinned in `tools/README.md`); `make e2e` builds and starts
the compose stack, seeds 20 students, runs both, and runs `docker compose down -v` afterwards. Output goes to
`target/e2e/`.

- `tools/e2e/happy.js`: student0001 commits a random 2 MB file, uploads to the signed URL, finalizes, and waits (up to 150 s) for VERIFIED; the teacher's dashboard shows the row VERIFIED, and the bytes behind the download URL hash to the committed SHA-256.
- `tools/e2e/failure.js` + `tools/e2e/failure.sh`: 5 students click at the same moment while Postgres is paused for 10 s, using the shared retry policy of `tools/k6/lib/flows.js`; afterwards `/export` shows every student VERIFIED with exactly one live row, and at least one student met a 503 and still finished. The 503 is identified by status: in this app every 503 is the `DATABASE_UNAVAILABLE` ProblemDetail (`GlobalExceptionHandler`).
