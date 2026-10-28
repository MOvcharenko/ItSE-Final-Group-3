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
| `api` | presentation | controllers + request DTOs (`api.dto`, validated, plain-language messages); every method has `@PreAuthorize`; errors are ProblemDetail via `api.error.ApiExceptionHandler` |
| `service` | service | transactions and department checks; return `service.view` records, never entities; throw `NotFoundException` / `ConflictingStateException` / `BusinessRuleException` |
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
| `service.CatalogService` | C | interface only; implementation to follow |
| `service.BookingService` | C | interface only; implementation to follow |
| `service.ImportService` | C | interface only; implementation to follow |

Service implementations take the caller from `CurrentUserProvider`, never from method arguments (NFR1.3, NFR1.4).

## REST API: catalog, bookings, search, import (Dev C)

Roles are enforced by `@PreAuthorize` and re-checked in the service. Students get 403 on all of it (TR3.2).

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/subjects` | Scheduler | |
| `POST /api/subjects` | Scheduler | 201; `department` is optional |
| `GET /api/rooms`, `GET /api/equipment` | Scheduler, Instructor | |
| `POST /api/rooms`, `POST /api/equipment` | Scheduler | 201 |
| `PATCH /api/rooms/{id}/status`, `PATCH /api/equipment/{id}/status` | Scheduler | body `{"status":"MAINTENANCE"}`; keeps booking history (FR2.5) |
| `POST /api/bookings` | Scheduler, Instructor | 201; one room or one equipment item per request (TR2.6) |
| `GET /api/bookings?status=` | Scheduler, Instructor | Scheduler sees all, Instructor only their own |
| `POST /api/bookings/{id}/approve` | Scheduler | 409 if an overlapping approval exists (NFR3.1) |
| `POST /api/bookings/{id}/cancel` | Scheduler | optional body `{"note":"..."}` |
| `GET /api/search/rooms?start&end&minCapacity&roomType&equipment` | Scheduler, Instructor | FR2.4 |
| `GET /api/search/equipment?start&end&itemType` | Scheduler, Instructor | FR2.4 |
| `GET /api/import/templates/{type}` | Scheduler | CSV download (FR3.5) |
| `POST /api/import/{type}` | Scheduler | multipart field `file`; 200 with a per-row outcome (FR3.6) |

- Import `{type}` is one of `ready-made-schedule`, `subject-list`, `rooms`, `equipment`.
- Date-times are ISO local, e.g. `2026-11-10T17:00:00`.
- A partly faulty import file is not an error. Only a wrong header or an empty or unreadable file is rejected (422).
- Uploads above Spring's multipart limit (1 MB by default) fail; raise `spring.servlet.multipart.max-file-size` if needed.

| Status | Meaning |
|---|---|
| 400 | invalid input; field messages in the `errors` property |
| 404 | `NotFoundException` |
| 409 | `ConflictingStateException`, including `BookingCollisionException` |
| 422 | `BusinessRuleException` |

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
