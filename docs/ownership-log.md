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
