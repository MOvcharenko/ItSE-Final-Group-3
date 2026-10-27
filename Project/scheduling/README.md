# Academic Scheduling module (Team 3, SDT 213)

Spring Boot 4.1.2 service with a REST API and a thin static web UI.

Lives in `Project/scheduling/`, next to the design material it implements:

| Folder | What | How it is used here |
|---|---|---|
| `Project/Phase 1/` | requirements report | source of FR/NFR/TC ids in test names |
| `Project/Phase 2/` | original prototype code (kept as the Phase 2 record) | ported to the `domain` package, one class per file, with the fixes from the development plan |
| `Project/UI Mockup/` | HTML prototype | design reference for the static UI pages |
| `src/main/resources/import-templates/` | CSV import templates (moved from `Project/Import Templates/`) | served by `GET /api/import/templates/{type}`; headers pinned by `ImportTemplatesTest` |

## Prerequisites

- JDK 21 or newer (CI uses Temurin 21; the code compiles with `--release 21`)
- No Maven install needed: use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows)

## Build, test, run

```sh
cd Project/scheduling
./mvnw verify                                        # unit tests (*Test) + integration tests (*IT)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The dev profile uses in-memory H2 in PostgreSQL mode, with the console at `/h2-console`.
`GET /actuator/health` is open; everything else needs authentication (JWT from Day 2).

## Packages (top level, directly under `src/main/java`)

| Package | Layer | Rule |
|---|---|---|
| `api` | presentation | controllers + DTOs; every method has `@PreAuthorize`; errors are ProblemDetail via `api.error.ApiExceptionHandler` |
| `service` | service | transactions and department checks; throw `NotFoundException` / `ConflictingStateException` / `BusinessRuleException` |
| `core` | business rules | **no Spring or JPA imports** |
| `persistence` | data access | JPA entities + repositories; schema only via Flyway (`src/main/resources/db/migration`) |
| `integration` | external | `SisClient` + stub; failures degrade |
| `security` | security | JWT config, `CurrentUserProvider` |
| `app` | entry point | `SchedulingApplication`; lists the packages Spring scans, so **add any new top-level package to `scanBasePackages`** |
| `domain` | shared | enums and the pure model ported from Phase 2 (no Spring/JPA); JPA entities are separate, in `persistence` |

Time: inject `java.time.Clock`; never call `LocalDateTime.now()` in services. Tests use `support.FixedClock`.

Spring tests outside the `app` package must name the application class, e.g. `@ContextConfiguration(classes = SchedulingApplication.class)` with `@WebMvcTest`, or `@SpringBootTest(classes = SchedulingApplication.class)`.

Test names carry the TC-ID they cover, e.g. `@DisplayName("TC-FR4.3c: ...")`.

## Cross-stream contracts (Day 1 stubs)

| Interface | Owner | Day-1 stub |
|---|---|---|
| `security.CurrentUserProvider` | A | `StubCurrentUserProvider` (Maya Brooks, scheduler, CS + MATH); JWT-backed from Day 2 |
| `service.NoticeService` | A | `LoggingNoticeService`; takes **ids** (`sectionId`, `bookingId`) until the entities exist |
| `ConflictService.recheck` | B | to be added by B |
| `SisClient` | B | to be added by B |

## Phase 2 port: what changed

- One class per file in `domain` (the Phase 2 files had several public classes per file and did not compile).
- `Room` has a unique `name` (import files use `room_name`); `Subject` has a `department` for scheduler authorization.
- `Term.publish()` no longer takes `confirmed`; the FR4.7 warning belongs in TimetableService.
- `AuditLogEntry` carries `conflictId`; dismissing needs a reason (NFR3.3).
- `Notice` carries `sectionId`/`termId` and a `NoticeEvent`.
- No `LocalDateTime.now()` in the model: timestamps are passed in from the injected `Clock`.
- `DomainModelTests` (Java `assert`, passed without checking unless run with `-ea`) is now JUnit 5 `DomainModelTest`.
- **Still open:** `Section` holds one meeting; Dev B replaces it with `MeetingTime` (weekly, one-time, exam).

## Working agreements

- Branches `feature/<area>-<what>`, PRs under ~400 lines, squash merge, `main` always green.
- Reviews rotate A → B → C → A. A change to someone else's contract is a PR they approve.
