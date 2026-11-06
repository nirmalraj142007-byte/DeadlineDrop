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
- Created `deploy/budget.sh` setting up a $40 budget alert (`dd-40`) at 50%, 90%, and 100% spend thresholds via `gcloud billing budgets create`.
- Drafted pilot outreach email in `docs/pilot/request-email.md` for prospective course instructors/club leads outlining opt-in anonymized telemetry, file retention, and deadline scheduling.

**Why:** Pre-registering experimental predictions prevents p-hacking or adjusting benchmark windows after observing load test results. Enforcing terminology via CI prevents concept drift from the approved system design, and establishing budget scripts and pilot materials secures operational discipline from day 1.

**Trade-off:** Pre-registering prediction thresholds before running test sweeps fixes the cohort evaluation window and prevents moving goalposts later. If Arm B misclassification falls below 5%, the pre-registered fallback mandates reporting raw counts per 1,000 students rather than widening the cohort window to inflate error rates.

**File to read first:** `PREDICTIONS.md` — pre-registration record containing the exact experimental hypotheses and embarrassment conditions against which all subsequent load and failure tests will be evaluated.

