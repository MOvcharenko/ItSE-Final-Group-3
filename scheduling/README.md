# Academic Scheduling module (Team 3, SDT 213)

Spring Boot 4.1.2 service with a REST API and a thin static web UI.

## Prerequisites

- JDK 21 or newer (CI uses Temurin 21; the code compiles with `--release 21`)
- No Maven install needed: use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows)

## Build, test, run

```sh
./mvnw verify                                        # unit tests (*Test) + integration tests (*IT)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The dev profile uses in-memory H2 in PostgreSQL mode, with the console at `/h2-console`.
`GET /actuator/health` is open; everything else needs authentication (JWT from Day 2).

## Packages (`team3.scheduling`)

| Package | Layer | Rule |
|---|---|---|
| `api` | presentation | controllers + DTOs; every method has `@PreAuthorize`; errors are ProblemDetail via `api.error.ApiExceptionHandler` |
| `service` | service | transactions and department checks; throw `NotFoundException` / `ConflictingStateException` / `BusinessRuleException` |
| `core` | business rules | **no Spring or JPA imports** |
| `persistence` | data access | JPA entities + repositories; schema only via Flyway (`src/main/resources/db/migration`) |
| `integration` | external | `SisClient` + stub; failures degrade |
| `security` | security | JWT config, `CurrentUserProvider` |
| `domain` | shared | enums and the pure model |

Time: inject `java.time.Clock`; never call `LocalDateTime.now()` in services. Tests use `support.FixedClock`.

Test names carry the TC-ID they cover, e.g. `@DisplayName("TC-FR4.3c: ...")`.

## Cross-stream contracts (Day 1 stubs)

| Interface | Owner | Day-1 stub |
|---|---|---|
| `security.CurrentUserProvider` | A | `StubCurrentUserProvider` (Jordan Ellis, scheduler, ENG); JWT-backed from Day 2 |
| `service.NoticeService` | A | `LoggingNoticeService`; takes **ids** (`sectionId`, `bookingId`) until the entities exist |
| `ConflictService.recheck` | B | to be added by B |
| `SisClient` | B | to be added by B |

## Working agreements

- Branches `feature/<area>-<what>`, PRs under ~400 lines, squash merge, `main` always green.
- Reviews rotate A → B → C → A. A change to someone else's contract is a PR they approve.
