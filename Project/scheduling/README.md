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
`GET /actuator/health` is open; everything else needs a JWT (see Signing in below).

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
| `security.CurrentUserProvider` | A | Done: `JwtCurrentUserProvider`, role from the token, departments from `scheduler_department` |
| `service.NoticeService` | A | Done: `PersistentNoticeService`. Takes text **ids** and saves in the caller's transaction, except students' notices, which follow after the commit. Dev B's Reassign: call `notifyAffected(sectionId, INSTRUCTOR_REASSIGNED)` and `notifyRemovedInstructors`. Dev C's booking decisions: call `notifyRequester(bookingId, event)`, after `markCancelled` so the note is included |
| `service.ConflictService` | B | `NoOpConflictService` finds nothing, so publishing never asks for confirmation until B replaces it |
| `service.TimetableService` | A | Done. Import: call `placeSection(termId, SectionPlacement)`; it applies the same rules as the API and throws `BusinessRuleException` with the reason |
| `integration.SisClient` | B | `StubSisClient` enrolls Jordan Ellis (`U03`) in every section. Has `enrolledStudentIds` and `enrolledSectionIds`; B adds the enrollment count and shared students |
| `service.CatalogService` | C | interface only; implementation to follow |
| `service.BookingService` | C | interface only; implementation to follow |
| `service.ImportService` | C | interface only; implementation to follow |

Service implementations take the caller from `CurrentUserProvider`, never from method arguments (NFR1.3, NFR1.4).

## Signing in

The module is a resource server with no login of its own (TR4.2). The role comes only from the token's `role` claim (`STUDENT`, `INSTRUCTOR` or `SCHEDULER`), and the display name from `name`. **The real Core Platform's claim names are still to be confirmed**; they are constants in `SecurityConfig`.

In the dev and test profiles, `POST /dev/token` plays the Core Platform. It needs no token, and it does not exist in any other profile.

| `userId` | Account | Role |
|---|---|---|
| `U01` | Maya Brooks | Scheduler (CS, MATH) |
| `U02` | L. Bianchi | Instructor |
| `U03` | Jordan Ellis | Student |

```sh
curl -X POST localhost:8080/dev/token -H "Content-Type: application/json" -d '{"userId":"U01"}'
curl localhost:8080/api/subjects -H "Authorization: Bearer <accessToken>"
```

Tokens last 8 hours but stop working when the app restarts, because the signing key is generated at startup.

Any other profile refuses to start unless `spring.security.oauth2.resourceserver.jwt.issuer-uri` is set.

**Adding an endpoint:** give it `@PreAuthorize` and add a row to `SecurityMatrixIT.ENDPOINTS`. That test fails until you do both. In tests that load the full application, use `@ActiveProfiles("test")`.

## REST API: terms and sections (Dev A)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/terms`, `GET /api/terms/{id}` | All | Students see only published terms; a Draft answers 404 for them |
| `POST /api/terms` | Scheduler | 201; always starts as a Draft |
| `PATCH /api/terms/{id}` | Scheduler | body `{"name":"..."}`; rename |
| `DELETE /api/terms/{id}` | Scheduler | 204; Draft only, removes its sections, and the caller must be authorized for all of them. A published term answers 409 |
| `POST /api/terms/{id}/copy` | Scheduler | 201; body `{"name":"..."}`; a new Draft with the same sections, and the caller must be authorized for all of them. A room now out of service is left empty |
| `POST /api/terms/{id}/publish?confirm=` | Scheduler | Open High conflicts answer 409 until `confirm=true` (FR4.7) |
| `GET /api/terms/{id}/sections` | Scheduler | |
| `POST /api/terms/{id}/sections` | Scheduler | 201; see the body below. Conflicts never stop the save |
| `PUT /api/terms/{id}/sections/{sectionId}` | Scheduler | Same body; replaces room, instructors, meetings and equipment. Subject and number stay fixed |
| `DELETE /api/terms/{id}/sections/{sectionId}` | Scheduler | 204; in a Published term those affected get a cancellation notice |
| `PATCH /api/sections/{id}/assignment` | Scheduler | `{"roomId":"...","instructorIds":["..."]}`; a field left out keeps its value (FR1.6) |

```json
{
  "subjectId": "...", "sectionNumber": "01", "roomId": "...", "instructorIds": ["U02"],
  "meetings": [
    {"pattern": "WEEKLY", "day": "MONDAY", "start": "10:00", "end": "10:50"},
    {"pattern": "EXAM", "date": "2026-12-15", "start": "09:00", "end": "11:00"}
  ],
  "equipment": [{"equipmentId": "...", "units": 2}]
}
```

`sectionNumber` (default `01`) and `equipment` are optional. Schedulers can only place or edit sections of subjects in their own departments; a subject with no department yet can be handled by any Scheduler with at least one. Publishing is not limited by department, because a term can span several. A change to a section's room, times or instructors in a Published term sends a notice. An equipment-only change re-checks conflicts but sends none, and an edit that changes nothing does neither.

This body is `service.SectionPlacement`, not a class in `api.dto`: schedule import builds the same object, so the service checks it rather than bean validation, and its problems answer 422.

Every change to a term or its sections first calls `TermRepository.lockById`, so changes to one term run one at a time. Any new code that changes sections must do the same, before loading them.

## REST API: notices (Dev A)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/notices` | All | The signed-in user's own notices, newest first |
| `POST /api/notices/{id}/read` | All | Safe to repeat. Someone else's notice answers 404 |

A change to a Published term notifies the section's instructors and enrolled students. The Scheduler who made the change is not told, unless they also teach the section. One edit sends one notice, even if it changes the room, times and instructors together; an instructor taken off the section gets their own "no longer teaching" notice instead. Changes to a Draft notify nobody. A notice stays after its section is removed, with `sectionId` empty.

Instructors' notices save with the change. Students' notices follow moments later on a background thread, once the change has committed, so a slow SIS holds up neither the Scheduler nor the term. If the SIS is down, the change still saves and instructors are still told, but those students are **not** notified later. The same happens if the app stops before their notices are saved.

## REST API: calendar (Dev A)

`GET /api/calendar` is open to all roles and returns one event per meeting per date, in time order.

| Parameter | Meaning |
|---|---|
| `view` | `DAY`, `WEEK` (default, Monday to Sunday) or `TERM` |
| `date` | ISO date inside the view; default today |
| `termId` | Limit to one term. `TERM` without it uses the only term on `date`, and answers 422 if there is none or more than one. With no `termId`, a Published term hides any Draft on the same dates, so a copied term doesn't double the calendar |
| `course`, `building` | Exact match, ignoring case |
| `instructor`, `room` | User id and room id |

| Role | Sees |
|---|---|
| Scheduler | Every section, including Drafts (see `termStatus` on each event). Pass `termId` to see a Draft that shares dates with a Published term |
| Instructor | Sections they teach, in Published terms |
| Student | Sections they are enrolled in according to the SIS, in Published terms |

A blank parameter means no filter, so a form can send its unset fields. Filters only narrow this, so they can never show someone more than their role allows. If the SIS is down, a student gets an empty list with `enrollmentAvailable: false`; the UI should say that enrollment could not be loaded, not that there are no classes.

### Calendar export

`GET /api/calendar.ics` downloads one whole term as an iCalendar file (FR6.3). It takes the same `termId`, `date`, `course`, `instructor`, `room` and `building` parameters as a `TERM` view and shows the same sections.

- A weekly meeting is one repeating event, and an exam or one-time meeting is a single event.
- Times have no time zone, so an app shows them at the same clock time wherever it runs.
- Draft sections, which only Schedulers can export, are marked `STATUS:TENTATIVE`.
- A meeting keeps its event ID when it moves, and `SEQUENCE` goes up, so a calendar app can treat a new import as an update. A meeting or section that was removed is simply missing from the new file, and most apps then keep the old event. Any new code that changes a section's room, meetings or instructors must call `SectionEntity.markModified`.
- If the SIS is down, a student's export answers 503, because an empty file would look like having no classes.
- The endpoint needs the bearer token, so the UI has to fetch the file and then offer it for download; a plain link will not work.

| Status | Meaning |
|---|---|
| 503 | `ServiceUnavailableException`: something this module depends on did not answer |

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

## Database and persistence

### The short version

- The database structure is defined in **one place**: the SQL files in `src/main/resources/db/migration`. Flyway runs them when the app starts.
- Hibernate (the JPA library) never creates or changes tables. It only **checks** that our Java classes match the tables (`spring.jpa.hibernate.ddl-auto=validate`).
- Each table that we read or write from Java has an **entity** class in the `persistence` package, plus a **repository** interface for queries.
- Services use repositories. Controllers never touch them.

### What is in the database

`V1__schema.sql` creates 23 tables. They fall into five groups:

| Group | Tables | Owner |
|---|---|---|
| People | `app_user`, `scheduler_department` | A |
| Timetable | `term`, `section`, `section_meeting`, `section_instructor`, `section_equipment`, `notice` | A |
| Catalogs and bookings | `subject`, `subject_equipment`, `room`, `room_fixed_equipment`, `equipment`, `booking`, `import_batch`, `import_row` | C |
| Conflicts and availability | `conflict`, `conflict_section`, `audit_log_entry`, `instructor_availability`, `availability_submission`, `instructor_equipment_need` | B |
| Insights | `insight_suggestion` | B |

Things worth knowing before you touch it:

- **A section can meet several times.** "MWF" is three rows in `section_meeting`. An exam is a row with a date instead of a weekday.
- **No roles and no passwords.** `app_user` stores only a name, email and department. The user's role is read from the login token on every request.
- **Students are not stored.** The SIS owns them. Enrollment numbers are asked from the SIS when needed, never copied here.
- **A Scheduler's departments** are stored in `scheduler_department`.
- **Names are unique** for subjects (`course_code`), rooms (`name`), equipment (`name`) and terms (`name`). The database comparison is case-sensitive, so also check with `existsBy...IgnoreCase` in the service.
- **Nothing that is still referenced can be deleted:** rooms, equipment, subjects, bookings. Change their status instead, so booking history is kept. Deleting a term removes its sections, meetings, conflicts and insights, but not bookings or the audit log.
- **The database refuses obviously wrong data:** zero or negative capacity, a start time that is not before the end time, a booking with both a room and an equipment item, a dismissed conflict without a reason, and so on. A service should still check first and give a friendly message, but a bug cannot silently store bad data.
- **Ids are text** (UUIDs) created by the application, except user ids, which come from the Core Platform.

### Changing the schema: migration rules

1. **Never edit a migration file that is already on `main`.** Flyway remembers a checksum of each file, and the app refuses to start if one has changed. Until your branch is merged you may edit your own new file freely.
2. **Every change is a new file** `V2__what_it_does.sql`, `V3__...`. Use plain numbers (not dates), because the tests sort files by that number.
3. **If two people create the same version number,** the one who merges second renumbers their file.
4. **No test or demo data in migrations.** Migrations also run in production. Put sample data in the dev/test loader (`Td1Loader`, or a `@Profile("dev")` runner). Remember that a Scheduler needs a row in `scheduler_department`, or every department check fails.
5. Keep the SQL working on both **PostgreSQL** and **H2 in PostgreSQL mode** (so, no vendor-only features).

### Looking at the database

Run the app with the dev profile and open `/h2-console`. The JDBC URL is the one in `application-dev`, which should be:

```
jdbc:h2:mem:sched;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
```

`SELECT * FROM flyway_schema_history;` lists the migrations that were applied.

### Entities: how they are written here

Existing entities: `AppUserEntity`, `RoomEntity`, `EquipmentEntity`, `SubjectEntity`, `BookingEntity`, `ImportBatchEntity` (with `ImportRowRecord`), `TermEntity`, `SectionEntity` (with `SectionMeeting`, `SectionEquipment`), `NoticeEntity`. Copy their style:

- **Extend `AssignedIdEntity`.** It holds the id and tells Spring Data that a new object is new, so `save()` inserts straight away.
- **Create new objects with the static `create(...)` method** (it generates the id). A fixtures loader can use the constructor that takes an id.
- **Write every table and column name explicitly:** `@Table(name = "room")`, `@Column(name = "room_type", ...)`. Never rely on automatic naming.
- **Declare `nullable = false` exactly where the column is NOT NULL.** The mapping test checks both directions.
- **Enums are `@Enumerated(EnumType.STRING)`.** The tables store text and have a CHECK listing the allowed values, so adding an enum value also needs a migration.
- **Relations are `LAZY`.** If a screen needs related data, write a repository query with `join fetch` (see `BookingRepository.findQueue`), so it is one query, not one per row.
- **Small lists** (such as a room's fixed equipment) are `@ElementCollection` with their own small table.
- **Services never return entities.** They map them to the records in `service.view`, which is what the API sends out.

### Repositories: what to know

- Names such as `findByNameIgnoreCase` and `existsByCourseCodeIgnoreCase` are generated by Spring Data from the method name.
- `RoomRepository.lockById` and `EquipmentRepository.lockById` lock one row until the transaction ends.
- `BookingRepository` has the queries for booking: overlap counts, busy rooms, equipment usage and the request queue.
- Rooms and equipment used by timetable sections are **not** included in the booking queries yet. `SectionEntity` and `SectionRepository` now exist, so availability search can and must also exclude those.

### How booking approval stays safe

Two Schedulers might approve overlapping requests for the same room at the same moment. To stop a double booking, the approving code does these steps in **one transaction**:

1. Lock the room (or equipment) row with `lockById`. A second approval for the same room waits here.
2. Count approved bookings that overlap the window (`countApprovedForRoom`).
3. If the count is 0 (for equipment: less than its `unit_count`), mark the booking approved. Otherwise throw `BookingCollisionException`, which the API answers with HTTP 409.

Two windows overlap only when each starts before the other ends. A booking that ends at 17:00 does **not** block one that starts at 17:00. Only approved bookings reserve a resource, and pending ones do not. The database alone cannot prevent this double booking. The lock in the service is what does.

### Adding a new entity (checklist)

1. Make sure the table exists in a migration.
2. Write the entity in `persistence`, in the style above, and a repository next to it.
3. Add the class to `EntityMappingTest.entities()`.
4. Run `./mvnw verify`. The mapping test names any wrong column, missing NOT NULL, text that is too long, enum mismatch, or required column that nothing fills.

### Tests

| Test | What it checks | Needs Spring? |
|---|---|---|
| `EntityMappingTest` | Applies the migrations to H2 and compares every entity with the real tables | No |
| `PersistenceIT` | Repository queries, the row lock, the overlap boundary, the queue fetch, import rows | Yes |

Spring needs to find the entities and repositories in `persistence`. `SchedulingApplication` lives in `app`, so it must contain `@EntityScan(basePackages = "persistence")` and `@EnableJpaRepositories(basePackages = "persistence")`. Check the `@EntityScan` import with your IDE, because Spring Boot 4 moved it.