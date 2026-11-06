# CLAUDE.md — DeadlineDrop

Read this file at the start of every session. It is the standing context for the
build prompt series (phases 0-42). The phase prompt you are given says WHAT to build
today; this file says HOW to build anything in this repo. If a phase prompt and this
file disagree, stop and ask me.

## What this project is

DeadlineDrop is a Spring Boot assignment-submission portal that never marks an
on-time student late because of slow Wi-Fi.

Core idea (a **hash commitment**): the browser computes the file's SHA-256, sends a
tiny commit request that the server stamps **on arrival**, uploads the bytes straight
to storage through a signed URL, then calls finalize. The hash binds the stamp to the
exact bytes; the grace window admits only bytes that were already committed.

The project is measured as an ablation of three builds from the same code:

| Arm | Spring profile | Bytes go through | Stamp taken at |
| --- | --- | --- | --- |
| A | `naive` | the app (multipart) | upload completion |
| B | `direct-finalize` | Cloud Storage (signed URL) | finalize, after bytes land |
| C | none (the real design) | Cloud Storage (signed URL) | commit arrival, before bytes move |

The headline number is arm B vs arm C. Arm A appears in the chart only.

Owner: Nirmal (solo intern). Calendar: day 1 = Fri 6 Nov 2026, day 22 = Fri 27 Nov 2026.

## Stack (do not add to it without asking)

- Java 21, Spring Boot 4.1.x, Maven wrapper (`./mvnw`)
- Spring MVC, Spring Security (OAuth2 resource server, HS256 JWT), BCrypt
- Spring Data JPA for users, courses, enrollments, assignments
- JdbcTemplate for submission state transitions and the job queue
- Flyway, `ddl-auto=validate`, PostgreSQL 17, HikariCP (pool 5, connection-timeout 2 s)
- Google Cloud: Cloud Run, Cloud SQL, Cloud Storage (V4 signed URLs via IAM signBlob,
  no key files), Cloud Scheduler (OIDC), Secret Manager — region **asia-south1** only
- Frontend: static HTML, CSS, vanilla ES modules in `src/main/resources/static`; no
  framework, no npm build, no CDN, no inline scripts or `style=` attributes
- Tests: JUnit Jupiter, Mockito, AssertJ, Testcontainers 2.0.x, Toxiproxy, ArchUnit
- Load testing: k6 v2 (pinned version in `tools/README.md`); analysis in Python 3.12
- Boot 4 uses Jackson 3 (`tools.jackson.*`). If a starter or artifact name differs from
  what Spring Initializr generates for Boot 4.1, use Initializr's name and tell me.

Never add: Redis, Kafka, WebSockets, Lombok, Kubernetes, microservices, any paid
third-party service, email/SMS/payment providers, OAuth/Google sign-in, AI features.

## Package layout

Base package `com.deadlinedrop`:

- `api` controllers, `api.dto` request/response records
- `service` business logic (`CommitService`, `FinalizeService`, `SubmissionStateMachine`, ...)
- `repository` JPA repositories and JdbcTemplate repositories
- `domain` entities, enums, row records
- `storage` `StoragePort` with `LocalStorageAdapter` and `GcsStorageAdapter`
- `security`, `jobs`, `web` (filters), `chaos`, `telemetry`, `config`

ArchUnit enforces: `api` never touches `repository`; `repository` never touches
`service` or `api`; `domain` never touches `api`; no field injection.

## Profiles

`local` (Docker Compose), `test`, `cloud`, `naive` (arm A), `direct-finalize` (arm B),
`storage` (local signed-URL storage server, no database), `seed`. Arm C = no arm profile.
Each arm uses its own database on the same instance: `dd_a`, `dd_b`, `dd_c`.

## Coding rules

- Constructor injection only. DTOs are Java records. JPA entities never leave the
  service layer.
- Every error is an RFC 9457 ProblemDetail with a `code` property (constants in
  `api/ErrorCodes`) and a `requestId`. No stack traces in responses.
- Status codes in use: 400, 401, 403, 404, 409, 410, 413, 415, 422, 503 (+ `Retry-After: 1`).
- Every controller method has `@PreAuthorize` or `@PublicEndpoint`.
- Time comes from the injected `java.time.Clock`. The **authoritative stamp** is
  `committed_at`, set in `CommitTimestampFilter` at request arrival.
  `db_committed_at` is kept only for audit. Never use SQL `now()` for fairness logic.
- SQL always uses bind parameters. No string-concatenated SQL with request values.
- No secrets in git. Use env vars; `.env` is git-ignored, `.env.example` is tracked;
  Secret Manager in the cloud.

## DO NOT CHANGE (from the approved blueprint and review)

1. Commit before transfer; SHA-256 in the browser with Web Crypto; server-side stamp on
   arrival; hash binding; grace admits only already-committed bytes.
2. Arm C never carries the bytes; uploads go directly to storage via signed URLs.
3. State machine `COMMITTED -> UPLOADED -> VERIFIED`, exits `EXPIRED`,
   `REJECTED_HASH_MISMATCH`, `SUPERSEDED`. Every transition is one
   `UPDATE ... WHERE id = ? AND status = <expected>` with rows-affected checked.
   Idempotency keys (unique per student) and the partial unique index
   `ux_live_submission ... WHERE status NOT IN ('EXPIRED','SUPERSEDED')`.
4. Jobs live in a Postgres table, claimed with `FOR UPDATE SKIP LOCKED`, enqueued in the
   same transaction as the transition, drained by an HTTP call (Cloud Scheduler with OIDC).
   Never `@Scheduled`.
5. PostgreSQL over MySQL; Flyway with `ddl-auto=validate`; migrations are forward-only and
   never edited after they are applied.
6. Ground truth for fairness is the k6 click time on NTP-synced VMs, with a 5-second margin.
7. `PREDICTIONS.md` is append-only. Never edit an existing line. New predictions go in a
   new section, in their own commit, after I approve the text.
8. Failure tests F1 (instance crash), F2 (browser dies after upload), F3 (database restart),
   with F3 reported against ourselves.
9. `StoragePort` with cloud and local adapters, and the separate local storage container.

## Vocabulary

- Say **hash commitment**, never "commit-reveal".
- Say **transactional job queue**, never "outbox".
- `make terms` (tools/check-terms.sh) fails CI if either banned term appears.
- The repeatable sentence is the review's version. Never claim "even with a server
  crashed mid-rush". The "database down at 11:59" clause is allowed only if the HMAC
  receipt shipped AND F3 with the receipt measured 0 misjudged.

## Testing rules

- Integration tests use Testcontainers Postgres and the real local storage adapter.
  Never mock the database, the state machine, or storage. Database outages are
  simulated with Toxiproxy (real database, real network fault).
- `*Test` = unit (surefire), `*IT` = integration (failsafe). `./mvnw verify` runs both.
- Race tests live in `src/test/java/com/deadlinedrop/race/`. `GuardRemovedRaceIT` must keep
  proving that removing the status guard breaks the invariant. Never weaken it.
- Fix production code, not tests, when a race or integration test fails, and tell me why.

## Evidence rules

- All evidence lives in `results/`. Counted runs (`meta.json` → `"counted": true`) are never
  edited by hand and never re-run to get a better number. A run may be repeated only when
  `meta.json` marks it invalid for an infrastructure reason; keep both folders.
- Dry runs are labelled `"counted": false`.
- Arms A, B and C must share CPU, memory, concurrency, max instances, pool size, Cloud SQL
  tier and inputs. `tools/parity.sh` must be clean before every run.
- Connection budget: Σ(maxInstances × pool) over deployed arms ≤ `max_connections − 5`.
  `tools/preflight.sh` enforces it.
- k6 VMs: at most 8 vCPU in total (free-trial cap). Skew above 100 ms aborts a run.
- After tag `evidence-frozen` (day 17), never modify the measured paths checked by
  `tools/check-frozen.sh`: `service/`, `repository/`, `jobs/`, `storage/`, `web/`,
  `db/migration/`, `tools/k6/`, `tools/run.sh`, `tools/analyze.py`, `tools/gen_schedule.py`,
  `results/runs/`.
- Every number in README, report or resume must come from a file in `results/`
  (`tools/check-claims.sh`). Never type a number from memory.

## Common commands

```bash
./mvnw verify                      # unit + integration tests
make up / make down / make logs    # Docker Compose (postgres, app, storage, scheduler)
docker compose --profile arms up   # also starts arm A (:8082) and arm B (:8083)
make seed N=1000 DB=dd_c           # seed teacher, admin, N students, DEMO101, Lab 1
make terms                         # banned-terms check
make k6-local ARM=c N=20           # local load run
make e2e                           # end-to-end happy + failure path (from phase 36)
make demo / demo-reset / demo-deadline SECONDS=45 / demo-rush / check   # (from phase 37)
bash deploy/deploy-arm.sh <a|b|c>  # deploy one arm to Cloud Run
bash deploy/smoke.sh <arm>         # cloud smoke test
```

Ports: app/arm C 8080, arm B 8083 (8081 in the demo stack), arm A 8082, storage 9000.

## Cloud safety

- Never create or download a service-account key. Deployment uses Workload Identity Federation.
- Ask me before any destructive or billable action you were not explicitly told to run:
  deleting resources, restarting Cloud SQL outside a planned F3 run, raising tiers or
  instance counts, creating VMs.
- Shutdown (phase 40, Thu 26 Nov) requires me to type "delete" before anything is removed.
- Budget alert `dd-40` (US$40) must stay in place.

## Data protection (pilot)

- Real pilot students' data is handled only as the phase prompts say: consent text v1,
  anonymised telemetry (HMAC of student id with a secret salt), export to the teacher,
  deletion by 26 Nov. `pilot/` is git-ignored; never commit names, emails or passwords.

## How every session ends

1. Run every acceptance check in the phase prompt and show me the output.
2. Append an entry to `docs/ownership-log.md`: date, phase, what changed, why, one
   trade-off, and the one file I should read first.
3. Commit with a descriptive message (never leave the repo broken: `./mvnw verify` green,
   `docker compose up` working).
4. Explain to me in plain language what you built and why. I must be able to defend
   every line in an interview, so explain the reasoning, not just the diff.

## Where to look

- `docs/decisions.md` — dated decisions (region, cuts, assumptions, pilot, gates)
- `PREDICTIONS.md` — pre-registered predictions (append-only)
- `docs/ownership-log.md` — one entry per session
- `results/README.md` — evidence layout
- `docs/design.md`, `docs/prior-art.md`, `docs/tests.md` — once they exist
