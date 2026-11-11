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
