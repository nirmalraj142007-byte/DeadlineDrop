# Ownership Log

One entry per session. Append only — never edit existing entries.

---

## Phase 0 — 2026-11-06

**What changed:** Created the full project skeleton from an empty repository.
Scaffolded `pom.xml` (Spring Boot 4.1.1 parent, all required starters, Surefire for
`*Test`, Failsafe for `*IT`), all twelve subpackages with `package-info.java`, the
main application class, four YAML config files (`application.yml`,
`application-local.yml`, `application-test.yml`, `application-cloud.yml`), a stub
Flyway migration directory, `SecurityConfig` (stateless, health public, everything else
authenticated), a multi-stage `Dockerfile` (JDK build / JRE runtime as non-root `app`),
`docker-compose.yml` with a Postgres 17 service and a healthy-depends app service,
`docker/postgres-init.sql` to create `dd_a` and `dd_b`, `.env.example`, `.gitignore`,
`.gitattributes` (LF for all, CRLF for `.cmd`/`.bat`), `TestcontainersConfiguration`,
`ApplicationSmokeIT`, `README.md`, and `docs/versions.md`.

**Why:** Phase 0 establishes a compilable, testable, containerisable baseline on which
every subsequent phase builds. Getting the dependency graph, Flyway validation mode and
HikariCP settings correct now prevents rework in later phases.

**Trade-off:** In Spring Boot 4 and Testcontainers 2.0.x, artifacts and test clients were
refactored: Testcontainers renamed module coordinates to `org.testcontainers:testcontainers-postgresql`
and `testcontainers-junit-jupiter`, and Boot 4 modularized test clients into
`spring-boot-resttestclient` with `@AutoConfigureTestRestTemplate`. In addition, PostgreSQL 17
strictly validates IANA timezones (rejecting legacy aliases such as `Asia/Calcutta`),
so JVM test runners explicitly enforce `-Duser.timezone=UTC`, and `DeadlineDropApplication`
defaults to UTC on startup.

**File to read first:** `pom.xml` — it pins the entire dependency graph (Boot 4.1.1,
Testcontainers managed by Boot BOM, ArchUnit 1.4.0 explicit) and configures the
Surefire/Failsafe split that governs how `./mvnw verify` works.

---

## Phase 1 — 2026-11-06

**What changed:** Implemented uniform RFC 9457 ProblemDetail error handling, request ID tracing, architecture testing, and CI workflow.
- In `pom.xml`, added `maven-dependency-plugin:properties` to resolve `-javaagent:${org.mockito:mockito-core:jar}` in Surefire and Failsafe arguments to eliminate the Mockito byte-buddy self-attachment warning on Java 21, keeping `-Duser.timezone=UTC`. Added Boot 4's `spring-boot-starter-webmvc-test` dependency for `@WebMvcTest`.
- Created `com.deadlinedrop.api.ErrorCodes` containing all 25 standardized error code constants.
- Created `com.deadlinedrop.api.ApiException` runtime exception with status, code, detail, and static factories.
- Created `com.deadlinedrop.web.RequestIdFilter` (`OncePerRequestFilter`, `HIGHEST_PRECEDENCE + 1`) to reuse incoming `X-Request-Id` UUID headers or generate a UUID, populate MDC key `requestId`, and echo the header in responses.
- Created `com.deadlinedrop.web.GlobalExceptionHandler` (`@RestControllerAdvice` extending `ResponseEntityExceptionHandler`), returning `ProblemDetail` with type `https://deadlinedrop.dev/problems/<lower-kebab>`, title, status, detail, instance URI, `code`, `requestId`, `errors` on validation failures, `Retry-After: 1` on 503 database unavailability, and sanitization of unexpected 500 errors with logged stack traces.
- Created `com.deadlinedrop.security.ProblemDetailWriter` using Jackson 3 to stream RFC 9457 JSON from Spring Security handlers. Updated `SecurityConfig` to disable CSRF (documenting why: bearer tokens in Authorization header with no cookies), disable httpBasic and formLogin, and route `authenticationEntryPoint` (401 UNAUTHENTICATED) and `accessDeniedHandler` (403 FORBIDDEN) through `ProblemDetailWriter`.
- Created `src/test/java/com/deadlinedrop/web/GlobalExceptionHandlerTest` testing all 15 error scenarios with filters disabled.
- Extended `ApplicationSmokeIT` to verify `GET /api/anything` returns 401 `UNAUTHENTICATED` with `application/problem+json` and `X-Request-Id` header, and converted tests to constructor injection.
- Created `src/test/java/com/deadlinedrop/ArchitectureTest` using ArchUnit to enforce layering (`api` -> `repository`, `repository` -> `service`/`api`, `domain` -> `api`) and forbid `@Autowired` field injection.
- Created `.github/workflows/ci.yml` with `build-test` and `secrets` (Gitleaks) jobs, and added the CI badge to `README.md`.

**Why:** A uniform error format prevents client-side ambiguity and data leakage, while correlation IDs ensure every error can be traced end-to-end. Layering and injection rules enforced in ArchUnit prevent architectural erosion as features are added in subsequent phases.

**Trade-off:** In Spring Boot 4.1.x, `@WebMvcTest` slice testing was modularized into `spring-boot-starter-webmvc-test` under `org.springframework.boot.webmvc.test.autoconfigure`. Subclassing `ResponseEntityExceptionHandler` requires overriding its protected template methods (e.g., `handleMethodArgumentNotValid`) instead of declaring duplicate `@ExceptionHandler` methods to avoid ambiguous handler mapping collisions in Spring MVC's dispatcher.

**File to read first:** `src/main/java/com/deadlinedrop/web/GlobalExceptionHandler.java` — it defines the centralized error contract and exception mapping logic for the entire API.

---

## Phase 2 — 2026-11-06

**What changed:** Recorded the day-1 project foundations, pre-registered experimental predictions, vocabulary enforcement, and project tooling:
- Pre-registered benchmark predictions in `PREDICTIONS.md` before any code producing results was written, covering definitions, 7 core hypotheses for Arms A, B, and C, embarrassment criteria, and the fallback reporting rule.
- Added quality rules to `AGENTS.md` and created dated project decisions D1 through D6 in `docs/decisions.md` (deployment region `asia-south1`, scope cuts and replacements, working assumptions, pilot decision gate, Claude Code usage cadence, credit balance).
- Created `tools/check-terms.sh` to enforce approved architectural terminology (requiring hash commitment and transactional job queue, and forbidding banned terms) across the repository while excluding `.git`, `target`, the script itself, and `AGENTS.md`. Integrated this check as a fast-failing first step in the `build-test` CI job in `.github/workflows/ci.yml`.
- Created project `Makefile` defining `verify`, `up`, `down`, `logs`, and `terms` targets.
- Created `deploy/budget.sh` setting up a budget alert (`dd-40`, representing roughly US$40, parameterized via required `BUDGET_AMOUNT` in billing account currency e.g. `3500INR`) at 50%, 90%, and 100% spend thresholds via `gcloud billing budgets create`.
- Drafted pilot outreach email in `docs/pilot/request-email.md` for prospective course instructors/club leads outlining opt-in anonymized telemetry, file retention, and deadline scheduling.

**Why:** Pre-registering experimental predictions prevents p-hacking or adjusting benchmark windows after observing load test results. Enforcing terminology via CI prevents concept drift from the approved system design, and establishing budget scripts and pilot materials secures operational discipline from day 1.

**Trade-off:** Pre-registering prediction thresholds before running test sweeps fixes the cohort evaluation window and prevents moving goalposts later. If Arm B misclassification falls below 5%, the pre-registered fallback mandates reporting raw counts per 1,000 students rather than widening the cohort window to inflate error rates.

**File to read first:** `PREDICTIONS.md` — pre-registration record containing the exact experimental hypotheses and embarrassment conditions against which all subsequent load and failure tests will be evaluated.

---

## Phase 3 — 2026-11-06

**What changed:** Implemented the complete PostgreSQL schema across five forward-only Flyway migrations and validated relational constraints with integration tests:
- Removed `src/main/resources/db/migration/.gitkeep`.
- Created `V1__users_courses_assignments.sql` defining `user_role` enum, `users`, `courses`, `enrollments`, and `assignments` tables with size constraints, deadline checks, and lower-cased unique email indexing.
- Created `V2__submissions_state_machine.sql` defining `submission_status`, `submission_timing`, and `event_actor` enums, the `submissions` table (without optimistic locking version column; status column guards state transitions), `submission_events` audit history, lowercase hexadecimal SHA-256 validation regex constraint, student-scoped idempotency unique index, and partial unique index `ux_live_submission` preventing multiple concurrent active submissions for an assignment.
- Created `V3__jobs.sql` defining `job_type`, `job_status`, and the `jobs` table for transactional job processing with unique constraint on `(type, submission_id)` and pending execution index `ix_jobs_ready`.
- Created `V4__telemetry_survey.sql` defining `telemetry_events` and `survey_responses` tables storing student-consented upload telemetry keyed by anonymous hash identifiers with deliberate exclusion of user foreign keys.
- Created `V5__baseline_submissions.sql` defining `baseline_submissions` table for experimental arms A and B recording direct upload / finalize completion stamps for ablation comparison.
- Created `src/test/java/com/deadlinedrop/repository/MigrationIT.java` using Spring Boot, Testcontainers PostgreSQL 17, and `JdbcTemplate` to verify migration history and enforce partial index behavior, regex constraints, grace window constraints, duplicate job rejection, and idempotency uniqueness.

**Why:** The database schema encodes DeadlineDrop's core transactional invariants directly into PostgreSQL rather than relying solely on application-level checks. State transitions, hash validity, idempotency, and concurrency limits are guarded by database-level constraints.

**Trade-off:** Omitting an optimistic locking `version` column on `submissions` departs from conventional JPA entity patterns. It explicitly delegates concurrency control to atomic single-row conditional updates (`UPDATE submissions SET status = ? WHERE id = ? AND status = ?`) and partial unique indexes (`ux_live_submission`). This avoids version churn and lock conflicts while allowing concurrent state checks with exact row-count validation.

**File to read first:** `src/main/resources/db/migration/V2__submissions_state_machine.sql` — it encapsulates the core state machine, the partial unique index guarding live submissions, and the audit log schema.


---

## Phase 4 — 2026-11-06

**What changed:** Implemented JPA entities and Spring Data repositories for users, courses, enrollments, and assignments:
- Created `com.deadlinedrop.domain.Role` enum (`STUDENT`, `TEACHER`, `ADMIN`).
- Created `com.deadlinedrop.domain.User` (`@Entity`, `@Table(name = "users")`) with UUID `@Id`, `email`, `passwordHash`, `role` mapped to PostgreSQL named enum `user_role` via `@Enumerated(EnumType.STRING)` and `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`, `displayName` with 100-character constraint validation, `@Version long version` optimistic locking, and `@CreationTimestamp`/`@UpdateTimestamp` audit fields.
- Created `com.deadlinedrop.domain.Course` with UUID primary key, unique `code`, `title`, plain `UUID teacherId` column (no `@ManyToOne` association), version, and timestamps.
- Created `com.deadlinedrop.domain.Enrollment` with composite key `@EmbeddedId` record `Enrollment.Id(UUID courseId, UUID studentId)` and `@CreationTimestamp createdAt`.
- Created `com.deadlinedrop.domain.Assignment` with UUID primary key, `courseId`, `title`, `deadlineAt`, `graceSeconds` (validated 0-3600), `maxBytes` (validated 1-26214400), `allowedTypes` mapped via `@JdbcTypeCode(SqlTypes.ARRAY)` to PostgreSQL `text[]`, `createdBy`, version, and timestamps. Added domain methods `graceEndsAt()`, `allows(String contentType)`, and `update(...)`.
- Created Spring Data JPA interfaces in `com.deadlinedrop.repository`: `UserRepository` (`findByEmailIgnoreCase`), `CourseRepository` (`findByTeacherId`), `EnrollmentRepository` (`existsById`, `findByIdStudentId`), and `AssignmentRepository` (`findByCourseIdInOrderByDeadlineAtAsc`).
- Created `src/test/java/com/deadlinedrop/repository/DomainRepositoryIT.java` verifying persistence, case-insensitive email lookup, role enum round-trip, PostgreSQL `text[]` array round-trip, ordered queries, enrollment lookups, and optimistic locking conflict detection via `ObjectOptimisticLockingFailureException`.

**Why:** Modeling the static and administrative domain structures (users, courses, enrollments, assignments) in JPA with Hibernate schema validation (`ddl-auto=validate`) ensures that type definitions, constraints, and optimistic locking are strictly validated against the Flyway schema at application startup. Dynamic submission transactions and the job queue remain isolated from JPA to avoid ORM overhead and session caching pitfalls.

**Trade-off:** Foreign key references (`teacher_id`, `course_id`, `created_by`) are intentionally mapped as primitive `UUID` columns rather than lazy `@ManyToOne` relationships. This prevents N+1 query surprises and detached entity graph issues during concurrent operations, keeping entity lifecycles decoupled. Concurrency control on `Assignment` uses standard JPA `@Version` optimistic locking, contrasting with `submissions` which uses database-level row guards.


---

## Phase 5 — 2026-11-06

**What changed:** Implemented stateless HS256 JWT authentication, method security enforcement, seed runner for benchmark cohorts, and architecture guards:
- Added `INVALID_CREDENTIALS` error code to `com.deadlinedrop.api.ErrorCodes` and added `ApiException.unauthorized(...)` helper.
- Created `com.deadlinedrop.config.ClockConfig` exposing `@Bean Clock systemUTC()` for consistent, testable wall-clock access.
- Created `com.deadlinedrop.config.PasswordEncoderConfig` exposing `@Bean PasswordEncoder = new BCryptPasswordEncoder(10)`.
- Created `com.deadlinedrop.security.PublicEndpoint` method-level marker annotation for deliberately unauthenticated endpoints.
- Created `com.deadlinedrop.security.JwtService` to issue HS256 tokens using `NimbusJwtEncoder` and `ImmutableSecret` from base64-decoded `JWT_SECRET` (failing startup if decoded key < 32 bytes) with claims `iss` ("deadlinedrop"), `sub` (user UUID), `role`, `name`, `iat`, and `exp` (`clock.instant() + dd.jwt.ttl`, default 12 hours).
- Updated `com.deadlinedrop.security.SecurityConfig` with `@EnableMethodSecurity`, `oauth2ResourceServer` JWT validation with `NimbusJwtDecoder`, `JwtAuthenticationConverter` mapping claim `role` to authority `ROLE_<role>`, permitting `POST /api/auth/login` and `/actuator/health/**`, and wiring `ProblemDetailWriter` for both 401 UNAUTHENTICATED and 403 FORBIDDEN.
- Created `com.deadlinedrop.api.dto.AuthDtos` containing records `LoginRequest`, `TokenResponse`, and `MeResponse`.
- Created `com.deadlinedrop.service.AuthService` handling `login` with constant-time dummy BCrypt hash evaluation when the email is missing (preventing user enumeration via timing analysis) and returning 401 `INVALID_CREDENTIALS` on password or user mismatch, plus `getMe(UUID)` lookup.
- Created `com.deadlinedrop.api.AuthController` with `@PublicEndpoint` `POST /api/auth/login` returning `TokenResponse` and `@PreAuthorize("isAuthenticated()")` `GET /api/me` returning `MeResponse`.
- Created `com.deadlinedrop.config.SeedRunner` (`@Profile("seed")`, `ApplicationRunner`) reading `SEED_STUDENTS` (default 50) and `SEED_PASSWORD` (required). Generates one BCrypt hash of `SEED_PASSWORD` for all load-test accounts, seeds admin, teacher, and students, creates course `DEMO101` and assignment `Lab 1` (due now + 7 days, grace 900 s, max 25 MiB, allowed MIME types pdf/zip/png), performs batch insertions with `ON CONFLICT DO NOTHING`, and exits with code 0 upon completion.
- Added `seed` target to `Makefile` and `SEED_PASSWORD` to `.env.example`.
- Extended `src/test/java/com/deadlinedrop/ArchitectureTest` with an ArchUnit rule asserting that every public method of a `@RestController` in `..api..` carries `@PreAuthorize` (on method or class) or `@PublicEndpoint`.
- Created `src/test/java/com/deadlinedrop/security/AuthIT` integration test validating 200 login with 12 h expiry, 401 `INVALID_CREDENTIALS` on wrong password, 401 on unknown email with identical body structure, 401 `UNAUTHENTICATED` without token or with expired token, and 403 `FORBIDDEN` when student calls a `@PreAuthorize("hasRole('TEACHER')")` endpoint.

**Why:** Stateless HS256 JWT tokens remove server-side session state and eliminate cross-site request forgery risks while keeping authentication overhead minimal during high-concurrency deadline rushes. Method-level security annotations coupled with strict ArchUnit enforcement prevent developer oversight from exposing endpoints, while constant-time login checks prevent user enumeration. The automated seed runner provides reproducible, idempotent student cohorts for subsequent k6 load tests.

**Trade-off:** Pre-computing a single BCrypt password hash for all 1,000 load-test student accounts allows seeding 1,000 users in under 200 ms, whereas individually hashing passwords would take over 80 seconds on CPU. Load-test accounts exist solely for automated benchmarks; production pilot student accounts will receive individual salts and hashes in Phase 30.

**File to read first:** `src/main/java/com/deadlinedrop/security/SecurityConfig.java` — it brings together JWT resource server decoding, role authority conversion, problem detail entry points, and endpoint authorization rules.

---

## Phase 6 — 2026-11-06

**What changed:** Implemented the storage abstraction (`StoragePort`), local signed-URL adapter (`LocalStorageAdapter`), standalone HTTP storage server (`StorageServerController`), security, and integration test suite:
- Added `SIGNATURE_INVALID` error code to `com.deadlinedrop.api.ErrorCodes`.
- Created `com.deadlinedrop.storage.StoragePort` interface declaring nested records `SignedUrl(String url, String method, Map<String,String> headers, Instant expiresAt)` and `ObjectInfo(long size, String contentType)`, and methods `signPut`, `signGet`, `stat`, `open`, `put` (arm A), and `delete`.
- Created `com.deadlinedrop.storage.StorageProperties` (`@ConfigurationProperties("dd.storage")`, `@Validated`) with `publicBaseUrl`, `root`, `hmacSecret`, `bucket`, and `server.enabled`.
- Created package-private `com.deadlinedrop.storage.StorageHmac` for URL-safe HMAC-SHA256 signing and constant-time verification using `MessageDigest.isEqual`.
- Created `com.deadlinedrop.storage.LocalStorageAdapter` (`@Component`, `@Profile("!cloud")`) generating HMAC-signed PUT and GET URLs, streaming byte open, atomic disk write with `.meta` sidecar for content type, stat, and delete.
- Created `com.deadlinedrop.storage.StorageServerController` (`@RestController`, `@ConditionalOnProperty("dd.storage.server.enabled")`) with `@PublicEndpoint` `PUT /storage/**` and `GET /storage/**`. Validates signature, expiry, content type match, path traversal safety, and max byte cap while streaming (aborting with 413 `FILE_TOO_LARGE` past max). Moves files and `.meta` sidecars atomically into place. Returns 403 `SIGNATURE_INVALID` on any signature, expiry, path, or type violation.
- Created `com.deadlinedrop.storage.StorageServerSecurityConfig` (`@ConditionalOnProperty("dd.storage.server.enabled")`, `@Order(0)`, `securityMatcher("/storage/**")`) permitting all requests without Bearer auth and allowing CORS from `http://localhost:8080`, `8081`, `8082`, and `8083`.
- Created `src/main/resources/application-storage.yml` running on port 9000 with storage server enabled and excluding database auto-configurations across both Spring Boot 4 modular packages (`org.springframework.boot.jdbc...`, `org.springframework.boot.hibernate...`, `org.springframework.boot.data.jpa...`, `org.springframework.boot.flyway...`) and legacy names.
- Annotated database-backed `AuthService` and `AuthController` with `@Profile("!storage")` so the storage server container requires no database.
- Updated `docker-compose.yml` to define `storage` service on port 9000, updated `app` with shared volume `objects:/data/objects` and `DD_STORAGE_HMAC_SECRET`, and added `DD_STORAGE_HMAC_SECRET` to `.env.example`.
- Created `src/test/java/com/deadlinedrop/storage/StorageIT` testing all required scenarios: valid signed PUT -> 200 with stat and open verification; missing signature -> 403; past TTL -> 403; path tampering -> 403; content-type mismatch -> 403; body > max bytes -> 413; signed GET -> 200 with `Content-Disposition: attachment; filename="<fn>"`.

**Why:** Arms B and C of DeadlineDrop rely on browsers uploading bytes directly to storage via signed URLs, eliminating application server bandwidth bottlenecks during deadline rushes. Implementing a dedicated local storage server with constant-time HMAC verification, size limits, and CORS support allows developing, testing, and benchmarking the direct-to-storage architecture locally before introducing Google Cloud Storage in Phase 8.

**Trade-off:** The storage server enforces limits during streaming rather than relying solely on the HTTP `Content-Length` header. While slightly more complex than checking `Content-Length` alone, streaming byte counting guarantees protection against clients spoofing headers or sending chunked transfer encoding payloads larger than the signed quota.

**File to read first:** `src/main/java/com/deadlinedrop/storage/StorageServerController.java` — it encapsulates the signed URL validation, constant-time signature verification, streaming byte-limit abort, and atomic file and metadata persistence.

---

## Phase 7 — 2026-11-11

**What changed:** Implemented Arm A (the tutorial-style upload baseline where the entire file passes through the application and is stamped only when upload finishes):
- Created `com.deadlinedrop.domain.Timing` enum (`ON_TIME`, `LATE`) matching PostgreSQL `submission_timing`.
- Created `com.deadlinedrop.service.DeadlinePolicy` returning `ON_TIME` when `!stamp.isAfter(assignment.getDeadlineAt())` and `LATE` otherwise.
- Created `com.deadlinedrop.security.CurrentUser` utility extracting user `UUID` and role string from JWT subject/claims or authentication context.
- Created `com.deadlinedrop.service.AssignmentAccess` validating assignment existence (404 `ASSIGNMENT_NOT_FOUND`) and student course enrollment (403 `NOT_ENROLLED`) for reuse across arms A, B, and C.
- Created `com.deadlinedrop.repository.BaselineSubmissionRepository` using `JdbcTemplate` to insert into `baseline_submissions` with enum casting `?::submission_timing` and count submissions by assignment.
- Created `com.deadlinedrop.service.BaselineSubmissionService` to mediate baseline persistence from the service layer to satisfy ArchUnit layer enforcement (`api` never touches `repository`).
- Created `com.deadlinedrop.api.dto.BaselineDtos` declaring record `BaselineSubmissionResponse(UUID submissionId, Instant submittedAt, String stampSource, String timing)`.
- Created `com.deadlinedrop.api.NaiveSubmissionController` (`@Profile("naive")`, `@PreAuthorize("hasRole('STUDENT')")`) providing `POST /api/assignments/{assignmentId}/naive-submissions`: validates access, rejects disallowed media types (415 `TYPE_NOT_ALLOWED`) and oversized files (413 `FILE_TOO_LARGE`), streams bytes to `StoragePort`, records arrival stamp post-transfer, evaluates timing via `DeadlinePolicy`, inserts baseline submission row with stamp source `UPLOAD_COMPLETE`, and responds 201 Created.
- Created `src/main/resources/application-naive.yml` setting `spring.servlet.multipart.max-file-size=25MB` and `max-request-size=26MB`.
- Updated `Dockerfile` to create `/data/objects` owned by `app:app` to ensure non-root container write permissions on mounted volumes.
- Updated `docker-compose.yml` adding service `app-arma` under compose profile `arms` on port 8082 with `SPRING_PROFILES_ACTIVE=local,naive` targeting database `dd_a`.
- Created `src/test/java/com/deadlinedrop/support/MutableClock` providing an explicitly settable and advanceable test clock.
- Created `src/test/java/com/deadlinedrop/service/DeadlinePolicyTest` testing T-1 ms (`ON_TIME`), exactly T (`ON_TIME`), and T+1 ms (`LATE`).
- Created `src/test/java/com/deadlinedrop/api/NaiveSubmissionIT` testing 1 MB PDF on-time upload (verifying 201, database row, and storage bytes on disk), clock moved past deadline (`LATE`), 26 MB upload (413 `FILE_TOO_LARGE`), text/plain upload (415 `TYPE_NOT_ALLOWED`), and unenrolled student upload (403 `NOT_ENROLLED`).
- Created `src/test/java/com/deadlinedrop/api/NaiveSubmissionDisabledIT` verifying 404 when the naive profile is inactive.

**Why:** Arm A establishes the empirical baseline representing conventional tutorial-style assignment submission architectures. Under slow or degraded network conditions, taking the submission timestamp after bytes finish transferring through the application server penalizes students with slow connections even if their transfer started well before the deadline. Implementing Arm A enables direct ablation comparison against Arm B (direct-to-storage finalize stamp) and Arm C (hash commitment arrival stamp).

**Trade-off:** Having the application server buffer and handle multipart uploads consumes application thread pool resources and server memory, contrasting with arms B and C which stream bytes straight to Cloud Storage via signed URLs. Stamping the submission post-transfer inherently binds the recorded timestamp to bandwidth and transfer latency. Additionally, to conform strictly with ArchUnit layering rules forbidding `api` classes from touching `repository`, baseline persistence operations are mediated by `BaselineSubmissionService`.

**File to read first:** `src/main/java/com/deadlinedrop/api/NaiveSubmissionController.java` — it encapsulates the tutorial-style upload flow, post-transfer timestamping, and size/type validation.

---

## Phase 8 — 2026-11-11

**What changed:** Deployed Arms A and C to Google Cloud Run in `asia-south1`, backed by Cloud SQL PostgreSQL 17 and Google Cloud Storage V4 signed URLs without service account keys:
- Updated `pom.xml` importing `com.google.cloud:libraries-bom:26.54.0` and adding `google-cloud-storage` and `com.google.cloud.sql:postgres-socket-factory:1.23.1`.
- Created `com.deadlinedrop.storage.GcsStorageAdapter` (`@Profile("cloud")`, implementing `StoragePort`): generates V4 PUT signed URLs enforcing `Content-Type` and `x-goog-content-length-range` extended headers, generates GET signed URLs with `response-content-disposition`, performs `stat`, `open` via `Channels.newInputStream`, `put` via `storage.createFrom`, and `delete`. On Cloud Run, IAM Credentials `signBlob` API signs directly via runtime service account `dd-run` without downloading service account keys.
- Created `com.deadlinedrop.config.GcsConfig` providing `Storage` bean under profile `cloud`.
- Updated `src/main/resources/application-cloud.yml` setting JDBC URL with Cloud SQL socket factory (`SocketFactory`), `dd.storage.bucket`, and `server.forward-headers-strategy=framework`.
- Created `deploy/env.sh` defining target region (`asia-south1`), Cloud SQL instance (`dd-pg`), tier (`db-f1-micro`), bucket, Artifact Registry repo, and service accounts.
- Created `deploy/arms.env` configuring standard container resource limits (`CPU=1`, `MEMORY=1Gi`, `CONCURRENCY=80`, `MAX_INSTANCES=1`, `MIN_INSTANCES=0`, `DB_POOL_MAX=5`, `TIMEOUT=300`) and documenting the connection budget (3 arms × 1 instance × pool 5 = 15 ≤ 25 - 5).
- Created `deploy/bootstrap.sh` (idempotent, describing before creating): enabled GCP APIs, created Artifact Registry repository, provisioned Cloud SQL `dd-pg` (Postgres 17, Enterprise tier `db-f1-micro`), created databases `dd_a`, `dd_b`, `dd_c` and user `dd`, stored passwords in Secret Manager (`dd-db-password`, `dd-jwt-secret`, `dd-seed-password`), created GCS bucket with uniform bucket-level access and public access prevention, created service accounts `dd-run` and `dd-deployer` with least-privilege IAM bindings (including self `roles/iam.serviceAccountTokenCreator` on `dd-run`), established Workload Identity Federation pool and OIDC provider for GitHub Actions, and configured GitHub repo variables.
- Created `deploy/deploy-arm.sh`: deploys Cloud Run services `dd-arm-a` (profiles `cloud,naive`, db `dd_a`) and `dd-arm-c` (profile `cloud`, db `dd_c`) with Cloud SQL connection, secrets, env vars, gen2 execution environment, and `--cpu-boost`; updates bucket CORS from live deployed service URLs; provides subcommand `seed <arm> <N>` creating and executing Cloud Run job `dd-seed-<arm>`.
- Created `.github/workflows/deploy.yml`: triggers on successful completion of `CI` workflow on `main`, authenticates via Workload Identity Federation without keys, builds and pushes container image to Artifact Registry, and deploys configured arms from `vars.DEPLOY_ARMS`.
- Created `deploy/smoke.sh`: tests actuator health `UP`, teacher login via Secret Manager seed credentials, and on arm A verifies student authentication, a 1 MB multipart upload (201 Created), and storage existence in GCS.
- Added comprehensive unit tests in `src/test/java/com/deadlinedrop/storage/GcsStorageAdapterTest.java` and recorded live Cloud Run URLs in `docs/decisions.md` D7.

**Why:** Deploying early to real cloud infrastructure in `asia-south1` ensures all IAM, networking, Cloud SQL socket connections, Cloud Storage signing, and GitHub Actions CI/CD pipelines are verified well ahead of load testing. Relying on Workload Identity Federation and the IAM `signBlob` API eliminates long-lived secret keys in accordance with cloud security best practices.

**Trade-off:** Cloud Run uses the IAM Credentials `signBlob` API rather than a local RSA private key file to produce GCS V4 signed URLs. While this adds a small IAM network call on signed URL generation, it completely eliminates key management risks, credential rotation overhead, and security key exposure.

**File to read first:** `src/main/java/com/deadlinedrop/storage/GcsStorageAdapter.java` — it encapsulates the V4 signed URL generation, IAM runtime signing, header bounding, and object lifecycle operations for Google Cloud Storage.


---

## Phase 9 — 2026-11-11

**What changed:** Conducted empirical buffering probe on Google Cloud Run to discover whether slow multipart uploads stream to the container or buffer at the Google front end:
- Created `com.deadlinedrop.web.RequestTimingLogFilter` (`@Component`, `@Order(Ordered.HIGHEST_PRECEDENCE + 2)`) filtering `POST /api/assignments/*/naive-submissions` to record structured log event `request_arrived` with `epochMs` and `contentLength`.
- Updated `com.deadlinedrop.api.NaiveSubmissionController` to emit structured log events `body_complete` at controller entry (after multipart parsing) and `stored` after `storagePort.put` completes.
- Rebuilt container image, pushed to Artifact Registry, and redeployed Arm A (`dd-arm-a`, revision `dd-arm-a-00006-rjv`) to Cloud Run in `asia-south1`.
- Created `tools/probe-buffering.sh`: generated a 20 MB random test file `probe.pdf`, authenticated as `student0001`, assigned fixed `X-Request-Id` `f6ca69e2-3c92-415d-81dd-7c27da5a5308`, uploaded throttled to 125 kB/s (~1 Mbps) via curl, recorded `clientStartMs` and `clientEndMs`, and queried Cloud Logging JSON events.
- Created `results/probe-buffering.md` documenting the empirical timing table: `clientStart` at 0.000 s, `request_arrived` at 164.554 s, `body_complete` at 164.689 s, `stored` at 165.568 s, and `clientEnd` at 165.381 s, establishing that Cloud Run buffers the full multipart body before dispatching the request to the container.
- Appended approved `Addendum 1 (day 6, 11 Nov 2026, buffering probe)` to `PREDICTIONS.md` in its own commit, updating Arm A's predicted failure mode from streaming thread exhaustion to container memory pressure from burst multipart spooling.

**Why:** Understanding whether Cloud Run streams or buffers request bodies is essential for modeling and predicting how Arm A behaves under deadline load. Streaming would mean slow clients hold Tomcat worker threads and concurrency slots for minutes; buffering means the Google front end holds connections and releases them to the container in sudden bursts when uploads complete, shifting the bottleneck to container memory and database connection contention.

**Trade-off:** In Spring Boot 4's ECS structured logging, MDC attributes (such as `requestId` set by `RequestIdFilter`) are automatically ingested into top-level JSON fields. Manually adding duplicate `requestId` key-value pairs triggered `assertNotDuplicateNestedPairs` in `ContextPairs`. Delegating `requestId` propagation to MDC while logging `epochMs` and `contentLength` via SLF4J KeyValue pairs prevents runtime log appender failures while ensuring all three lifecycle events correlate cleanly in Cloud Logging.

**File to read first:** `results/probe-buffering.md` — it contains the timing table, raw Cloud Logging events, and conclusion proving Cloud Run front-end buffering.


---

## Phase 10 — 2026-11-11

**What changed:** Built the commit step of the hash commitment for arm C only.
- `domain/Submission` (nested `Status`, `Actor`, `Row`), `web/CommitTimestampFilter` (stamps `ARRIVED_AT` from the injected `Clock` at `HIGHEST_PRECEDENCE` for `POST /api/assignments/*/commits`), `repository/SubmissionRepository` (JdbcTemplate; the supersede is one `WITH old AS (... FOR UPDATE) UPDATE ... RETURNING`), `service/CommitService`, `api/dto/CommitDtos`, `api/CommitController` (`@Profile("!naive & !direct-finalize")`, 201 new / 200 replay, `Server-Timing: app, db, sign`).
- `CommitService` runs one transaction: idempotent replay or 409 `IDEMPOTENCY_KEY_REUSED`, then 415/413 checks, `DeadlinePolicy.timing`, supersede, insert with `committed_at = arrivedAt`, events, `superseded_by`. A `DuplicateKeyException` on `ux_sub_student_key` or `ux_live_submission` re-runs the block once. The signed PUT URL is created only after the transaction commits, and a signing failure logs `sign_failed` and returns `uploadUrl: null` instead of losing the stamp.
- Tests: `CommitTimestampFilterTest`, `CommitIT` (16 cases incl. `committed_at` equals the MutableClock instant, a real PUT to the returned URL, supersede + `superseded_by`, concurrent commits, `Server-Timing`), `CommitDisabledUnderNaiveIT` (404 under `naive`). `GlobalExceptionHandlerTest` now imports `ClockConfig` because the `@WebMvcTest` slice loads servlet filters, which need a `Clock`.
- Built and pushed the image, redeployed `dd-arm-c` (revision `dd-arm-c-00006-cdb`) and committed as `student0001` on Lab 1: 201 with a GCS V4 URL, then 200 on replay.

**Why:** The stamp is the whole point of the design. It has to be taken before any slow work (security, body parsing, DB, signing), stored under the idempotency key, and survive a signing failure, so a student who got a stamp can always ask for a new upload URL later.

**Trade-off:** Both unique-violation cases (same-key race, different-key race) are handled by one generic retry of the whole transaction instead of two bespoke recovery paths. It is simpler and correct for both, but a second consecutive collision returns 409 `VERSION_CONFLICT` and the client must retry.

**File to read first:** `src/main/java/com/deadlinedrop/service/CommitService.java` — the transaction order, the retry on unique violations, and why signing happens after commit.


---

## Phase 11 — 2026-11-11

**What changed:** Built arm B, the baseline a competent engineer writes today: direct upload to storage, then finalize stamps the time.
- `api/DirectUploadController` (`@Profile("direct-finalize")`): `POST .../direct-uploads` (415/413 checks, signed PUT URL, no database write) and `POST .../direct-uploads/{uploadId}/finalize` (object path rebuilt from the authenticated student id, 404 `BYTES_NOT_FOUND`, 422 `SIZE_MISMATCH` plus delete, `submittedAt = clock.instant()` after the bytes landed, `stampSource FINALIZE`).
- `BaselineSubmissionRepository.insertIfAbsent` (`ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING`) and `findByIdempotencyKey`, reached through `BaselineSubmissionService.insertIfAbsentAndRead` because ArchUnit forbids `api` touching `repository`. New `domain/BaselineSubmissionRow`.
- `docker-compose.yml`: `app-armb` under profile `arms` (`local,direct-finalize`, `dd_b`, port 8083). `DirectFinalizeIT` (13 cases). `tools/direct-flow.sh` runs URL, PUT, finalize against any base URL.
- Fixed a phase 10 regression: the `storage` container would not start because `CommitService` needs a transaction manager. Database-dependent beans (`CommitService`, `CommitController`, `AssignmentAccess`, `BaselineSubmissionService`, both JDBC repositories) are now `@Profile("!storage")`.
- Set `DEPLOY_ARMS` to `a b c`; CI deployed `dd-arm-b`; seeded `dd_b` with 50 students; bucket CORS now lists all three arm URLs. The connection budget is unchanged: 3 x 1 x 5 = 15 <= 20.
- Verified the flow locally on :8083 and on `dd-arm-b`: both return `stampSource FINALIZE`.

**Why:** Arms B and C differ only in when the stamp is taken, so the B-vs-C comparison isolates the hash commitment. `DirectFinalizeIT` includes a case where finalize after the deadline is LATE even though the upload began before it, which is the unfairness arm C removes.

**Trade-off:** Replay safety comes from reusing `uploadId` as the idempotency key and reading the row back, instead of adding a state machine. It keeps arm B a faithful "typical" baseline, but it means arm B has no `COMMITTED` state, so a student whose browser dies after the PUT has no record until they call finalize.

**Gotchas found:** Cloud Run's front end rejects a POST without `Content-Length` (411), so a curl finalize needs an empty body; browsers send it automatically. The cloud signed URL also requires the `x-goog-content-length-range` header, so clients must send every header in `uploadHeaders`.

**File to read first:** `src/main/java/com/deadlinedrop/api/DirectUploadController.java` — see where the stamp is taken after `stat`, and how the object path is built from the authenticated student.


---

## Phase 12 — 2026-11-11

**What changed:** Built the second half of the core: a committed submission moves to UPLOADED exactly once, whether the browser's finalize or the reconciler gets there first.
- `repository/TransitionRepository`: `transition(id, from, to, now)` is one `UPDATE ... WHERE id = ? AND status = ?` that also sets the matching `uploaded_at` / `verified_at` / `expired_at`, and returns rows affected. `expireIfGraceOver` expires only a COMMITTED row whose `deadline_at + grace_seconds` is before the app-clock `now` passed in (no SQL `now()`).
- `jobs/JobRepository.enqueueVerify`: `INSERT ... ON CONFLICT (type, submission_id) DO NOTHING`.
- `service/SubmissionStateMachine`: `markUploaded` and `expire`, each `@Transactional`; only the caller whose UPDATE changed the row writes the event and enqueues the VERIFY job, in the same transaction; both return the status re-read from the row. Nested `RaceHook` test seam with a no-op production bean.
- `service/FinalizeService`: 404 `SUBMISSION_NOT_FOUND`, 403 `FORBIDDEN` for another student, returns the current status if not COMMITTED, 404 `BYTES_NOT_FOUND` before the PUT, 422 `SIZE_MISMATCH` / `CONTENT_TYPE_MISMATCH` after deleting the object. `service/ReconcilerService.settle`: bytes present with the right size -> UPLOADED; absent -> `expire` (no-op until grace is over).
- `api/FinalizeController`: `POST /api/submissions/{id}/finalize` -> `SubmissionStatusResponse(submissionId, status, timing, uploadedAt)`.
- Tests: `race/FinalizeVsReconcilerRaceIT` (50/50 iterations: one UPLOADED event, one job, both callers see UPLOADED), `race/GuardRemovedRaceIT` (same race without the guard: invariant broken in 50/50), `api/FinalizeIT` (8 cases incl. reconciler upload and expiry exactly after grace). `./mvnw verify`: 35 unit + 74 integration, all green.
- `tools/commit-flow.sh`: arm C commit -> PUT -> finalize against any base URL. Deployed `dd-arm-c` revision `dd-arm-c-00010-v28` (image `a6aeac6`); cloud flow returned 200 `UPLOADED`, `committedAt` 13:55:34Z unchanged, `uploadedAt` 13:55:42Z.

**Why:** After the PUT, two actors can both see COMMITTED: the browser calling finalize and the reconciler cleaning up after dead browsers. Without a guard both would write an UPLOADED event and the audit trail would say the upload happened twice. The guarded UPDATE lets Postgres pick exactly one winner: the second UPDATE waits on the row lock, re-checks `status = 'COMMITTED'` after the first commits, and matches zero rows. The barrier hook forces both racers to read COMMITTED before either writes, so the test hits the dangerous interleaving on every iteration instead of by luck, and `GuardRemovedRaceIT` proves the test would catch a regression.

**Trade-off:** The finalize endpoint reads the row twice (once in `finalize`, once in `describe` for the response) to keep the `Status finalize(...)` signature the race tests use. One extra primary-key read per finalize is cheap next to the storage `stat` call, and it keeps the racing code path identical to production.

**Assumptions flagged:** `FinalizeController` also excludes the `storage` profile, like `CommitController`, because that container has no database. The reconciler leaves a wrong-size object alone (finalize rejects it); it does not check content type because both signed URLs (GCS and local) bind the type. Content types are compared on the base type, case-insensitive. Local `./mvnw verify` must run outside the agent sandbox on Windows: the sandbox blocks the JDK's loopback pipe and Tomcat fails to start.

**File to read first:** `src/main/java/com/deadlinedrop/service/SubmissionStateMachine.java` — the rows-affected check is the whole idea; then `race/GuardRemovedRaceIT.java` to see it fail without the guard.


---

## Phase 6 fix — 2026-11-11

**What changed:** Removed every swallowed exception in `storage/`.
- New package-private `storage/LocalObjectFiles.stat`, used by both `LocalStorageAdapter.stat` and the local storage server's GET. It returns empty only on `NoSuchFileException` (object or `.meta` missing) or when the path is not a regular file; any other `IOException` becomes `UncheckedIOException("Failed to stat object <path>")`; unreadable or incomplete metadata becomes `IllegalStateException` naming the path. Both reach `GlobalExceptionHandler` as a 500 `INTERNAL` with a `requestId`.
- `StorageServerController` GET: missing object or metadata -> 404 `BYTES_NOT_FOUND` (also if the file vanishes between stat and open); other I/O errors are wrapped with the path.
- `StorageHmac.verify`: catches only the Base64 `IllegalArgumentException` from a malformed signature (-> false -> 403). It now reuses `sign`, so a broken HMAC setup throws instead of looking like a bad signature.
- `StorageIT`: missing object -> empty; object without `.meta` -> empty; corrupt `.meta` -> exception naming the object.

**Why:** AGENTS.md forbids swallowing exceptions. The old `catch (Exception ignored)` turned a corrupt or unreadable metadata file into `application/octet-stream`, which phase 12's finalize would then reject as `CONTENT_TYPE_MISMATCH` and delete the student's file: a disk problem would have looked like the student's fault.

**Trade-off:** An object whose `.meta` file is missing now counts as absent instead of `application/octet-stream`. Writers move the object before the metadata, so this treats a half-finished write as "not arrived yet" (finalize answers 404 and the browser retries) rather than as a wrong type. The cost: an object whose metadata was lost for good stays invisible until someone repairs it.

**File to read first:** `src/main/java/com/deadlinedrop/storage/LocalObjectFiles.java`.


---

## Phase 13 — 2026-11-11

**What changed:** Four more concurrency tests in `src/test/java/com/deadlinedrop/race/`. All passed on the first run; no production code changed.
- `race/HttpRaceSupport`: shared fixture for tests that go through the real HTTP endpoints (one student, course, assignment, a JWT signed with wall-clock times, helpers for commit, PUT to the in-process storage server, finalize). The `@HttpRaceTest` meta-annotation gives three of the tests one Spring context and one Postgres container.
- `DuplicateRetryIT`: 20 threads send the same commit (same Idempotency-Key and body) at once, 10 rounds. Every round: one 201, nineteen 200, one submissionId, one row, one COMMIT event. Then the same key with another sha256 -> 409 `IDEMPOTENCY_KEY_REUSED`. The full run logged 36 commit retries, so the unique-index retry path was really exercised.
- `SupersedeOnResubmitIT`: v1 commit, PUT, finalize (UPLOADED); v2 with a new key supersedes v1 (`superseded_by = v2`, one `UPLOADED -> SUPERSEDED` event by `NEW_COMMIT`). Then two commits with different keys at once, 10 rounds: never a 500, exactly one live row after each round, every superseded row has a successor. The retry ran in all 10 rounds. A raw JDBC insert of a second live row fails with SQLSTATE 23505 on `ux_live_submission`.
- `ExpiryAtGraceEndIT`: commit at T-10 s, no PUT. `settle` at grace end - 1 s -> COMMITTED; at grace end + 1 s -> EXPIRED with one RECONCILER event. Late PUT (URL still valid) is accepted by storage, but finalize returns EXPIRED and a second `settle` stays EXPIRED; 0 UPLOADED events, 0 jobs.
- `JobEnqueueAtomicityIT`: a `@Primary` `JobRepository` subclass throws `DataAccessResourceFailureException` from `enqueueVerify`. Finalize -> 503 `DATABASE_UNAVAILABLE` with `Retry-After: 1`; the row is still COMMITTED with no `uploaded_at`, 0 jobs, 0 UPLOADED events. After the fake recovers, the retried finalize gives UPLOADED, 1 job, 1 event.
- `docs/decisions.md` D8: the pilot gate line for Nirmal to fill.
- `./mvnw verify`: 35 unit + 81 integration, all green.

**Why:** Phase 12 proved finalize vs reconciler. These cover the other ways two writers meet one student's submission: browser retries (idempotency key), a resubmit racing a resubmit (partial unique index plus one retry), the reconciler racing a late upload at grace end (EXPIRED is final), and a crash between the transition and its job (one transaction, so it is all or nothing).

**Trade-off:** `ExpiryAtGraceEndIT` uses a 300 s grace instead of the 900 s default. With 900 s, a URL signed at T-10 s expires at T+890 s, before grace ends, so storage itself would refuse the late PUT and the test would prove nothing about the state machine. The cost: the test does not run at the production grace value. It also shows that, with a 900 s grace, a student who commits in the last 10 s before the deadline must re-request a URL if their upload has not started within 15 minutes.

**Assumptions flagged:** The failed enqueue throws a database-style exception, so the test expects 503 + `Retry-After` (the browser retries) instead of 500. `SupersedeOnResubmitIT` accepts 201 or 409 `VERSION_CONFLICT` for the concurrent pair; in practice both got 201 because the loser retried and superseded the winner. Tests run outside the agent sandbox on Windows (loopback pipe), as in phase 12.

**File to read first:** `src/test/java/com/deadlinedrop/race/SupersedeOnResubmitIT.java` — then `CommitService.runWithOneRetry`, which it exercises on every round.


---

## Phase 12 fix — 2026-11-11

**What changed:** Integration tests now sign their JWTs with wall-clock time.
- New `support/TestTokens.accessToken(jwtService, user)`: issued at `Instant.now()`, valid 1 hour.
- Used in every IT that pairs a `MutableClock` with `jwtService.issue(user)`: `FinalizeIT`, `CommitIT`, `DirectFinalizeIT`, `NaiveSubmissionIT`; `race/HttpRaceSupport` now calls the same helper instead of its own copy.
- Not changed: `AuthIT`, `CommitDisabledUnderNaiveIT`, `NaiveSubmissionDisabledIT` use the real system clock (no `MutableClock`), so `issue(user)` is already wall-clock; `FinalizeVsReconcilerRaceIT` and `GuardRemovedRaceIT` call services directly and send no token.
- `./mvnw verify`: 35 unit + 81 integration, all green. No production code changed.

**Why:** `JwtService.issue(user)` takes `iat`/`exp` from the injected `Clock`, which these tests replace with a `MutableClock` set to 20 Nov 2026 (up to 21 Nov 00:15 in the grace tests). The resource server checks `exp` against the real system clock. So tokens issued at simulated 20 Nov 12:00 + 12 h TTL would be rejected as expired (401) on any run after about 21 Nov 2026 00:00 UTC, and these suites would fail for a reason that has nothing to do with the code under test. The app's deadline logic (commit stamp, LATE, grace, URL expiry) still reads the `MutableClock`; only the test's login credential moved to real time.

**Trade-off:** The tokens now carry an `iat` that disagrees with the simulated "now" the app sees. Nothing in the app compares the two, so this is harmless today; a future feature that checks token age against the app clock would need these tests revisited.

**File to read first:** `src/test/java/com/deadlinedrop/support/TestTokens.java`.


---

## Phase 14 — 2026-11-11

**What changed:** The background half of the core: a transactional job queue in Postgres, drained by an HTTP call.
- `jobs/JobRepository`: `claimNext(now)` (`FOR UPDATE SKIP LOCKED`, refuses to run outside a transaction), `markDone`, `markRetry`, `markFailed`. `markRetry`/`markFailed` return whether they applied (see trade-offs).
- `domain/JobRow`: the claimed row (id, submissionId, attempts).
- `service/SubmissionStateMachine`: `markVerified` and `markRejected` (UPLOADED -> VERIFIED / REJECTED_HASH_MISMATCH, actor VERIFIER, one event), same guarded UPDATE.
- `jobs/VerifierService`: streams the stored object through a `DigestInputStream` (SHA-256, 64 KB buffer) and compares with the committed hash. Rows that are no longer UPLOADED are skipped. Logs `submission_verified` / `submission_rejected`.
- `jobs/DrainService`: one job per transaction (claim, verify, markDone) until the budget runs out. On failure, a new transaction records attempt n: retry after 1, 2, 4, 8 minutes; the 5th failure marks the job FAILED.
- `jobs/ReconcilerSweep` + `SubmissionRepository.findCommittedBefore`: COMMITTED rows older than 2 min, oldest first, 200 per call, each through `ReconcilerService.settle`.
- `api/InternalController`: `POST /internal/jobs/drain` (45 s budget) and `POST /internal/reconcile`, both `hasRole('INTERNAL')`.
- `security/InternalSecurityConfig` (+ `InternalTokenFilter`, `InternalTokenProperties`): an `@Order(1)` chain for `/internal/**`. Under local/test, `X-Internal-Token` compared with `MessageDigest.isEqual` grants ROLE_INTERNAL; the token is validated at startup (non-blank, 32+ chars). Under every other profile, including cloud, it denies all. Failures are 401 ProblemDetails with `code` and `requestId`.
- `docker-compose.yml`: `scheduler` service (`curlimages/curl:8.11.1`) that calls reconcile, then drain, every 60 s. `INTERNAL_TOKEN` was added to the app's environment and to `.env.example`.
- `jobs/JobsIT` (7 cases): verified, rejected (same size, different bytes), job for a superseded row is DONE with no change, backoff 1/2/4/8 min then FAILED at attempt 5 (and never claimed early), two drainers on 20 UPLOADED submissions (split 10 + 10, each verified exactly once), reconciler sweep (stale with bytes -> UPLOADED, stale without -> waiting, fresh skipped, all expire after grace), and endpoint auth (no token, wrong token, student JWT -> 401; right token -> 200).
- `./mvnw verify`: 35 unit + 88 integration, all green. Docker: commit -> PUT -> finalize, and the scheduler had the row VERIFIED 57 s after finalize. An unauthenticated drain returned 401.

**Why:** The hash commitment is only closed when someone hashes the stored bytes and compares them with the committed sha256. That work must not run on the student's request path. It also cannot run on a background thread: Cloud Run throttles CPU between requests, so a `@Scheduled` loop would stall. A scheduler calling an HTTP endpoint gives the work a request, and with it CPU. Keeping jobs in Postgres means a job exists if and only if its transition committed. `SKIP LOCKED` lets several instances drain together without running a job twice.

**Trade-off:** Claim, hash and markDone run in one transaction, so a drainer holds the row lock and one of the 5 pool connections while it hashes one object (at most 25 MB, read in-region). The gain is that a crashed drainer just drops the lock and the job runs again; there is no "in progress" status to clean up. The cost: during a drain, the commit path has one fewer connection per drainer. A second, smaller one: the failure is recorded after the first transaction rolled back, so in that gap another drainer can claim the same job. Verify is guarded by status, so this is harmless; `markRetry` then reports that it did not apply (logged as `applied=false`) instead of throwing.

**Assumptions flagged:**
- The phase prompt refers to "every case in the definition of done", but no definition of done was included. The 7 test cases are my reading of the task; check them against the real list.
- The deny-all chain covers every profile except local/test, not only `cloud`, so a misconfigured profile can never leave `/internal/**` open.
- The candidate query sits in `SubmissionRepository`, not inline in `ReconcilerSweep`, to keep SQL in repositories.
- The sweep lets an exception from one row (e.g. storage `stat` failing) fail the whole call instead of skipping the row.
- A FAILED job leaves its submission UPLOADED. Nothing alerts on it yet.
- `DrainResult`/`ReconcileResult` are records nested in their services and returned as-is by the controller.
- The local seed's assignment id is generated, so the acceptance flow ran with `ASSIGNMENT_ID` set. Tests ran outside the agent sandbox, as in earlier phases.

**File to read first:** `src/main/java/com/deadlinedrop/jobs/DrainService.java` — then `JobRepository.claimNext`, which it depends on.


---

## Phase 14 follow-up — 2026-11-11

**What changed:**
- `jobs/ReconcilerSweep`: an exception while settling one row no longer stops the sweep. It is logged at ERROR (`event=reconcile_row_failed`, `submissionId`, `requestId` from the MDC, plus the cause), counted in a new `errors` field of `ReconcileResult(settled, expired, waiting, errors)`, and the sweep moves on to the next row. The row stays COMMITTED and is tried again on the next sweep. One exception is deliberate: `DataAccessResourceFailureException` (database unreachable, which includes "no connection from the pool") is rethrown, so the call fails visibly.
- `jobs/JobsIT` now has one named test per required case: `matchingBytesAreVerifiedAfterOneDrain`, `differentBytesAreRejectedAsHashMismatch`, `twoDrainersOverTwentyUploadedSubmissionsProcessEachExactlyOnce`, `vanishedObjectIsRetriedWithBackoffAndFailsAfterFiveAttempts`, `sweepMovesStaleUnfinalizedCommitWithBytesToUploadedWithOneVerifyJob`, `sweepExpiresCommitWithNoBytesAfterDeadlinePlusGrace`. The first four were renamed from phase 14. The old combined sweep test was split into the last two plus `sweepLeavesFreshCommitsAndCommitsWithoutBytesInsideGraceAlone`.
- New `sweepContinuesPastARowThatFails`: three stale commits with bytes. The oldest one's `.meta` sidecar is overwritten with garbage, so the real local adapter's `stat` throws for it. Result `(2, 0, 0, 1)`: the two behind it become UPLOADED and the broken one stays COMMITTED. After the metadata is repaired, the next sweep settles it.
- `./mvnw verify`: 35 unit + 91 integration, all green.

**Why:** The sweep takes rows oldest first. Before this change, one row that always failed (corrupt metadata, a permission problem on one object) would sit at the head of every sweep and stop all the rows behind it, so stranded submissions would never be settled.

**Trade-off:** If the database itself is down, continuing would make each of up to 200 rows wait out the 2 s pool timeout, about 400 s, far past the scheduler's request. So that one class of error stops the sweep instead of being counted. The cost: a database blip during one row's settle aborts the rest of that sweep; the next run 60 s later picks them up.

**File to read first:** `src/main/java/com/deadlinedrop/jobs/ReconcilerSweep.java`.

---

## Phase 15 — 2026-11-12

**What changed:**
- `security/InternalSecurityConfig` (every profile except local/test): `/internal/**` now accepts a Google-signed OIDC token from Cloud Scheduler. `GoogleOidcTokens` builds `NimbusJwtDecoder.withIssuerLocation("https://accounts.google.com")` (key fetches: 2 s connect, 3 s read) with validators for expiry, issuer, `aud` contains `INTERNAL_AUDIENCE`, `email` equals `SCHEDULER_SA_EMAIL`, and `email_verified` true. A valid token gets ROLE_INTERNAL; anything else gets a 401 ProblemDetail. `InternalOidcProperties` (`dd.internal.oidc.*`): if both variables are blank (arms A and B, which have no scheduler) the chain still denies all; if only one is set, startup fails. The chosen mode is logged as `event=internal_auth_mode`.
- `chaos/Halter`, `RuntimeHalter` (`Runtime.halt(1)` on its own thread, 200 ms after the call, so the 202 is flushed first) and `ChaosController`: `POST /admin/chaos/halt` returns 202 `{"halting": K_REVISION or host name}`, and `GET /admin/chaos/status` returns `{"enabled": true}`, both ADMIN only. They exist only when `dd.chaos.enabled=true` (env `CHAOS_ENABLED`, default false); otherwise the routes 404.
- `web/GlobalExceptionHandler`: `CannotCreateTransactionException` -> 503 `DATABASE_UNAVAILABLE` + `Retry-After: 1`. A second handler covers any other `DataAccessException`/`TransactionException`: if a JDBC connection failure is in the cause chain (SQLState class 08, `SQLTransientConnectionException`, or Hikari's state-less "Connection is closed" for a connection it already evicted), it returns that 503; otherwise it keeps the 500. `application.yml`: `hikari.validation-timeout: 1000`.
- Tests: `web/DatabaseDownIT` (Postgres reached only through Toxiproxy; after one warm-up commit, the proxy is disabled and two commits during the outage are both 503 `DATABASE_UNAVAILABLE`, `Retry-After: 1`, under 2500 ms; the proxy is re-enabled and the same Idempotency-Key gives 201). `chaos/ChaosEndpointIT` (two contexts: flag off -> 404 for an admin; flag on -> teacher 403, admin 202, counting halter called exactly once, status 200). `security/GoogleOidcTokensTest` (7 cases) and 4 new `GlobalExceptionHandlerTest` cases. pom: `org.testcontainers:testcontainers-toxiproxy` (it brings `eu.rekawek.toxiproxy:toxiproxy-java` 2.1.11 with it; no other artifact added).
- `deploy/scheduler.sh`: creates the `dd-scheduler` service account if missing, checks that arm C's `INTERNAL_AUDIENCE` equals its URL, and creates or updates `dd-reconcile-c` and `dd-drain-c` (every minute, POST, OIDC audience = URL, 60 s attempt deadline). `deploy/smoke-flow.sh c`: login, random 1 MB file, commit, PUT with the returned headers, finalize (UPLOADED), then polls Cloud Logging for `submission_verified` with that id for up to 150 s. `deploy/deploy-arm.sh`: arm C gets `INTERNAL_AUDIENCE=<its status.url>` and `SCHEDULER_SA_EMAIL`. On a first-ever deploy it sets them right after, since the URL does not exist yet. The `--set-env-vars` separator changed from `@` to `|`, because the email contains `@` (the first Deploy run of this phase failed on exactly that for arm C).
- `./mvnw verify`: 46 unit + 94 integration, all green. Cloud: arm C revision `dd-arm-c-00017-mqz` logged `mode=google_oidc`. An unauthenticated drain returned `401 Unauthorized`. Cloud Scheduler logged `AttemptFinished 200` for `dd-drain-c` at 09:48 and 09:49 UTC. `smoke-flow.sh c` printed VERIFIED 36 s after finalize.

**Why:** Phase 14's queue only ran locally; in the cloud `/internal/**` denied everything, so nothing was ever verified there. Cloud Scheduler with OIDC needs no shared secret: Google signs a token for a service account, and the app checks who signed it, for whom (aud = our URL, so a token minted for another service cannot be replayed here) and as whom. The chaos switch is what F1 needs to crash an instance mid-rush the way a crash really happens (no shutdown hooks). The 503 work is the precondition for F3. When Cloud SQL restarts, a student's commit must fail within about 2 s with "retry in 1 s", not hang for the 300 s request timeout while the deadline passes.

**Trade-off:** `Runtime.halt` makes the crash realistic (no graceful shutdown, pool connections dropped on the floor, an in-flight request lost). The cost: the endpoint is a kill switch on a public URL. Admin-only is not enough on its own, so the route does not exist unless `CHAOS_ENABLED=true` is set on that deployment. A smaller one: recognising Hikari's "Connection is closed" depends on an exact message from Hikari 7.0.2. The alternative was validating every borrowed connection (`aliveBypassWindowMs=0`), which adds a round trip to every commit on the measured path. DatabaseDownIT runs against the real pool, so a Hikari upgrade that changes the message fails the build.

**Assumptions flagged:**
- `/admin/chaos/**` goes through the normal JWT chain (ADMIN role from our own login), not a separate chain.
- The local test JVMs on this machine needed `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/ddtmp`: Java NIO's AF_UNIX loopback failed in the default temp dir ("Unable to establish loopback connection", which also broke the existing CommitIT). This is environment only and is not in the repo; CI on Ubuntu does not need it.
- DatabaseDownIT sends one normal commit before the outage. Without it, the first request on a fresh JVM (client setup, servlet init, first JWT decode) added about 750 ms and the timing was not about the outage. The 2500 ms limit is unchanged.
- The first outage request in DatabaseDownIT hit a connection that died mid-transaction (the pool hands out a connection used in the last 500 ms without checking it), which gave a 500 until the handler learned the class-08 / evicted-connection case. That was a real production gap, fixed in production code.
- `CHAOS_ENABLED` is not set on any cloud arm yet; F1 will set it on the arm under test.
- No `roles/run.invoker` is granted to `dd-scheduler`: the arms are deployed `--allow-unauthenticated`, so the app's own OIDC check is the gate.

**File to read first:** `src/main/java/com/deadlinedrop/security/InternalSecurityConfig.java` — then `GoogleOidcTokens.validator`.

---

## Phase 16 — 2026-11-12

**What changed:** Implemented the complete teacher and student domain REST endpoints, service layer, export providers for ablation arms, and comprehensive test suite:
- `api/dto/DomainDtos.java`: defined records `CreateCourseRequest`, `CourseDto`, `EnrollRequest`, `EnrollResult`, `CreateAssignmentRequest`, `UpdateAssignmentRequest`, `AssignmentDto`, `MySubmission`, `StudentAssignmentDto`, `ExportRow`, and `TimeResponse` with validation constraints.
- `service/CourseService`: course creation with duplicate code check, listing own courses for teachers and enrolled courses for students with student counts, and batch enrollment returning counts for newly enrolled, already enrolled, and unknown emails.
- `service/AssignmentService`: assignment creation and lookup with ownership and enrollment authorization checks; `PATCH` updating assignment via `Assignment.update` after checking optimistic locking version (`ObjectOptimisticLockingFailureException` -> 409 `VERSION_CONFLICT`) and refusing post-deadline updates with 409 `WRONG_STATE`; `getMyAssignments` reading enrollments, assignments ordered by deadline ascending, and mapping the student's live submission per assignment.
- `service/ExportSource` + implementations: `SubmissionExportSource` (`!naive & !direct-finalize`) reading from `submissions` (`stampedAt = committed_at`, `stampSource = "COMMIT"`, including superseded and expired rows) and `BaselineExportSource` (`naive | direct-finalize`) reading from `baseline_submissions` (status `"SUBMITTED"`, `stampSource` from the row), both joining `users` for `studentEmail`.
- `service/ExportService`: verifies assignment existence and authorizes ADMIN or owning TEACHER before querying the active `ExportSource`.
- Controllers: `CourseController` (`POST /api/courses` TEACHER -> 201, `GET /api/courses` TEACHER/STUDENT, `POST /api/courses/{id}/enrollments` TEACHER owner); `AssignmentController` (`POST /api/courses/{courseId}/assignments` TEACHER owner -> 201, `GET /api/assignments/{id}` owner or enrolled student, `PATCH /api/assignments/{id}` owner, `GET /api/me/assignments` STUDENT); `TimeController` (`GET /api/time`, `@PublicEndpoint`, permitted in `SecurityConfig`, `Cache-Control: no-store`); `ExportController` (`GET /api/assignments/{id}/export?format=json|csv`, ADMIN or owning TEACHER, CSV with header row).
- `SecurityConfig`: permitted unauthenticated `GET /api/time`.
- `docs/decisions.md`: updated D3 clarifying that the export endpoint is API-only (no UI button).
- Tests: `DomainApiIT` (17 tests covering course, assignment, time, and arm C export endpoints), `DomainExportNaiveIT` (testing export under `naive`), and `DomainExportDirectFinalizeIT` (testing export under `direct-finalize`). Full `./mvnw verify` green with 46 unit tests and 113 integration tests passing.

**Why:** The UI and k6 benchmark load harness require endpoints to create courses and assignments, enroll students, synchronize client clock against server time, check assignment deadline status and student live submissions, and export submission records for grading and post-experiment evaluation across all ablation arms.

**Trade-off:** When fetching `my-assignments`, live submissions are queried with a single student-scoped query (`status NOT IN ('EXPIRED', 'SUPERSEDED')`) and indexed in memory by assignment ID. This decouples submission queries from JPA entity navigation, avoids multi-table outer join complexity, and retains constant-time lookup for each enrolled assignment.

**File to read first:** `src/main/java/com/deadlinedrop/service/AssignmentService.java` — it encapsulates the assignment lifecycle rules, ownership checks, optimistic locking version validation, deadline mutation protection, and live submission mapping.

---

## Phase 16 fix — 2026-11-12

**What changed:** Added explicit boundary limit verification for assignment creation in `DomainApiIT`:
- Added test `createAssignmentBoundaryValues` in `src/test/java/com/deadlinedrop/api/DomainApiIT.java`:
  - `graceSeconds`: 3600 -> 201 Created; 3601 -> 400 Bad Request with code `VALIDATION_FAILED` and `"graceSeconds"` in ProblemDetail `errors`.
  - `maxBytes`: 26214400 -> 201 Created; 26214401 -> 400 Bad Request with code `VALIDATION_FAILED` and `"maxBytes"` in ProblemDetail `errors`.
- Production validation in `DomainDtos.CreateAssignmentRequest` (`@Min(0) @Max(3600)`, `@Min(1) @Max(26214400)`) and `Assignment.validate` strictly match and enforce these boundary constraints.
- `./mvnw verify`: 46 unit + 114 integration tests, all green.

**Why:** Enforces that exact domain specification limits (1 hour max grace period, 25 MiB max upload size) are verified against the HTTP API contract and that exceeding boundaries returns descriptive RFC 9457 field-level error details.

**Trade-off:** Boundary checks run against real Testcontainers PostgreSQL instead of unit-level mocks, verifying that both Bean Validation at the HTTP layer and entity-level guards stay aligned.

**File to read first:** `src/test/java/com/deadlinedrop/api/DomainApiIT.java` — `createAssignmentBoundaryValues`.


---

## Phase 17 — 2026-11-12

**What changed:** Implemented the DeadlineDrop load generation harness and executed the live verification flow:
- Installed and pinned Grafana k6 `v2.3.0` (`k6.exe v2.3.0 (commit/e088784614, go1.26.8, windows/amd64)`).
- Created `tools/gen_schedule.py`: Python CLI supporting `--mode placeholder` and `--mode bootstrap`, generating student schedules partitioned into cohort shards (`shard-<cohort>.json`, `shard-all.json`, `schedule-meta.json`). In placeholder mode, generates log-normal upload speeds (medians 1, 4, 20 Mbps), size buckets {1,2,5,10,15,25} MB capped by `--max-mb`, hash calculation $T_{\text{hash}} = \text{size} / 80\text{ MB/s}$, and a convex last-minutes arrival curve concentrating 40% of cohort clicks in the final 60 s.
- Created `tools/k6/files/make-files.sh`: generates test payloads for each size bucket in `tools/k6/files/out/` (git-ignored).
- Created `tools/k6/lib/flows.js`: browser-identical retry policy using full jitter exponential backoff (base 500 ms, cap 8 s, retry on 0, 429, 5xx; never on 4xx; constant `Idempotency-Key`; give up at $T + \text{grace}$). Implements `armC`, `armB`, and `armA` flows logging structured JSON per virtual user iteration.
- Created `tools/k6/rush.js`: multi-VU load execution script using `k6/experimental/fs` to open files once per VM, rate-limiting logins in `setup()` to $\le 20$/s, sleeping until scheduled click offsets, sleeping hash duration (arm C only), running the corresponding arm flow, and writing `summary.json`.
- Created `tools/k6/sanity.js`: single VU check uploading a 20 MB file through Arm C, printing commit RTT and throughput in Mbps.
- Created `tools/k6/run-local.sh` and updated `Makefile` with `k6-local ARM=<a|b|c> N=<num>`: automated end-to-end local load test running against ports 8080 (c), 8083 (b), and 8082 (a), waiting for $T + \text{grace} + 2\text{ min}$ for transactional job queue reconciliation, and fetching `/export` to report counts by status.
- Updated `docker-compose.yml` to inject `INTERNAL_TOKEN` into `app-arma` and `app-armb`.
- Created `tools/README.md`: documents usage, pinned versions, and what `PLACEHOLDER` means.
- Successfully executed live flow against Cloud Run service `dd-arm-c` with $N=5$, verified all 5 reached `VERIFIED` status upon drain, and tagged git repository `flow-live`.

**Why:** The load generator is written once and relied upon for all subsequent benchmark and evidence runs. Pinned schedules, identical retry semantics, and unified logging ensure that differences observed across arms A, B, and C stem solely from backend architectural design rather than client measurement artifacts.

**Trade-off:** Virtual user payloads are opened via `k6/experimental/fs` at init rather than pre-loading entire ArrayBuffers across all VUs upfront. This prevents virtual user memory duplication from exhausting load generator RAM under high concurrency (1,000 VUs), while caching buffers locally within each VU during execution to preserve upload performance.

**File to read first:** `tools/k6/lib/flows.js` — encapsulates the exact client-side execution path, retry backoff logic, and status emission for all three comparison arms.
