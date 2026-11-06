# Dependency Versions — Phase 0

Resolved versions from Spring Boot 4.1.1 BOM and build dependencies:

| Component | Resolved Version | Notes |
|---|---|---|
| Spring Boot | 4.1.1 | Parent POM |
| Java | 21 | Eclipse Adoptium OpenJDK 21 |
| Maven Wrapper | 3.9.9 / Wrapper 3.3.2 | Built with `./mvnw` |
| Flyway | 12.4.0 | `flyway-core`, `flyway-database-postgresql` |
| Testcontainers | 2.0.5 | `testcontainers-postgresql`, `testcontainers-junit-jupiter` |
| JUnit Jupiter | 5.12.2 / 5.14.2 | Managed by Boot 4.1.1 BOM |
| ArchUnit | 1.4.0 | `com.tngtech.archunit:archunit-junit5:1.4.0` |
| PostgreSQL Driver | 42.7.13 | Managed by Boot 4.1.1 BOM |
| HikariCP | 7.0.2 | Managed by Boot 4.1.1 BOM |
| Jackson 3 | 3.1.5 | `tools.jackson.core:jackson-core:3.1.5` |
