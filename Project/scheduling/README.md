# Academic Scheduling module (Team 3, SDT 213)

Spring Boot 4.1.2 service with a REST API and a thin static web UI.

Lives in `Project/scheduling/`, next to the design material it implements:

| Folder | What | How it is used here |
|---|---|---|
| `Project/Phase 1/` | requirements report | source of FR/NFR/TC ids in test names |
| `Project/Phase 2/` | original prototype code (kept as the Phase 2 record) | ported to the `domain` package, one class per file, with the fixes from the development plan |
| `Project/UI Mockup/` | HTML prototype | design reference for the static UI pages |
| `Project/account-service/` | stand-in Core Platform: accounts, passwords, sign-in (its own app, port 9000) | optional; the `accounts` profile signs in through it. Not part of this module |
| `src/main/resources/import-templates/` | CSV import templates (moved from `Project/Import Templates/`) | served by `GET /api/import/templates/{type}`; headers pinned by `ImportTemplatesTest` |

## Prerequisites

- JDK 21 or newer (CI uses Temurin 21; the code compiles with `--release 21`)
- No Maven install needed: use the wrapper (`./mvnw`, or `mvnw.cmd` on Windows)

## Build, test, run

Step-by-step commands, including PowerShell, are in [commands.md](commands.md).

```sh
cd Project/scheduling
./mvnw verify                                        # unit tests (*Test) + integration tests (*IT)
./mvnw spring-boot:run                               # profile "local": test-account picker, http://localhost:8080/
./mvnw spring-boot:run -Dspring-boot.run.profiles=accounts   # sign in with passwords through Project/account-service
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # like local, but an in-memory database, for API work
```

The local and accounts profiles keep their H2 database (PostgreSQL mode) in `./data`; the dev profile's is in memory. Local and dev have the console at `/h2-console`.
`GET /actuator/health` and the web UI's own files are open; everything else needs a JWT (see Signing in below).

## Web UI

Open `http://localhost:8080/` and pick a test account. With the accounts profile, sign in at the account service instead (`m.brooks` / `Scheduler#2026`; all accounts are in [commands.md](commands.md)). The pages are plain HTML, CSS and JavaScript modules in `src/main/resources/static`, with no build step or framework. `Project/UI Mockup/` is the design reference.

| File | What it does |
|---|---|
| `js/app.js` | Sign-in, sidebar and top bar. The `SCREENS` list decides which role sees which screen, so a new screen goes there |
| `js/api.js` | All calls to the API: adds the token, turns errors into plain-language messages, and returns to sign-in on a 401 |
| `js/dom.js` | `el()` builds elements. Text from the API always goes in as text, so don't use `innerHTML` with it |
| `js/dates.js` | Date and time formats. Dates are local text such as `2026-11-04`, so avoid `toISOString`, which can shift the day |
| `js/forms.js` | Labelled fields and `openForm()`, the pop-up form that keeps what was typed and shows the API's message if a save fails |
| `js/calendar.js`, `js/notices.js` | The calendar screen (Day, Week, Term, filters, `.ics` export) and its notices panel. "Show courses as" (code and name, code, or name) is kept per browser in `localStorage`. The sidebar shows the unread count on every screen and checks it each minute |
| `js/timetable.js` | Schedulers: create, edit (name and Draft dates), copy, publish and delete terms; add, edit and remove sections, as a week grid or a list. Shows the term's open conflicts and links to them |
| `js/timetable-grid.js` | The Timetable week grid. Each weekly meeting is a block: drag it to another day or time (5-minute steps), or click it to edit. While dragging, times when its instructors or room are already in use are shaded and the preview turns red on a clash. "Show busy times for" shades one instructor's teaching as well as their declared unavailable and preferred availability blocks |
| `js/conflicts.js` | Schedulers: the conflicts in a term, filtered by state, type and severity, with the dismissal log, Re-scan, Dismiss (with a reason), Reassign (room and instructors, saved through `PATCH /api/sections/{id}/assignment` so the timetable's own checks apply) and "Open in timetable", which opens that section's form on the Timetable screen |
| `js/insights.js` | Schedulers: Optimization Insights screen with room occupancy and fill rates against standard 50-hour week, faculty workload metrics, positive confirmations, dismissable suggestion cards, and direct links to open conflicts |
| `js/catalog.js` | Schedulers: add subjects, rooms and equipment, and change a room's or item's status |
| `js/import.js` | The "Import from file" panel on the Catalog screen: template download, upload and row-by-row results |
| `js/bookings.js` | Schedulers and Instructors: request a room or equipment, and see the requests, filtered by status. Schedulers also approve, decline and cancel them |
| `js/search.js` | The "Search availability" panel on the Bookings screen, for rooms and equipment |
| `js/teaching.js` | Instructors: My Teaching screen with their assigned sections, workload calculations, interactive weekly availability grid (Open, Prefer, Unavailable), portable equipment checklist, and draft/published term lock |

- **Done:** sign-in (test accounts by default, username and password through the account service), the calendar and notices for all three roles, the Scheduler's Timetable, Conflicts, Insights, and Catalog screens, the Instructor's My Teaching (schedule, workload, availability grid, and equipment needs), booking requests and decisions, availability search, timetable availability lens, and CSV import. Publishing asks for confirmation when the term has open High-severity conflicts.
- **Sign-in:** this module never signs anyone in with a password and keeps no accounts (see Signing in). Accounts belong to the Core Platform, played by `Project/account-service` until it exists.

The token and the term last chosen are kept in `sessionStorage`, so they end with the browser tab, and each tab can use a different account. Signing out clears both. The role read from the token only chooses what to show; the API still checks every request.

## Packages (top level, directly under `src/main/java`)

| Package | Layer | Rule |
|---|---|---|
| `api` | presentation | controllers + request DTOs (`api.dto`, validated, plain-language messages); every method has `@PreAuthorize`; errors are ProblemDetail via `api.error.ApiExceptionHandler` |
| `service` | service | transactions, role and department checks, `Caller` (the one class that reads `CurrentUserProvider`); `*ServiceImpl` classes; returns `service.view` records, never entities; throws `NotFoundException` / `ConflictingStateException` (incl. `BookingCollisionException`) / `BusinessRuleException` |
| `core` | business rules | **no Spring or JPA imports**; `core.importing` holds the CSV parser and the row rules for imports |
| `persistence` | data access | JPA entities + repositories; schema only via Flyway (`src/main/resources/db/migration`) |
| `integration` | external | `SisClient` + stub; failures degrade |
| `security` | security | JWT config, `CurrentUserProvider` |
| `app` | entry point | `SchedulingApplication`; lists the packages Spring scans, so **add any new top-level package to `scanBasePackages`** |
| `domain` | shared | enums and the pure model ported from Phase 2 (no Spring/JPA); JPA entities are separate, in `persistence` |

Time: inject `java.time.Clock`; never call `LocalDateTime.now()` in services. Tests use `support.FixedClock`.

Spring tests outside the `app` package must name the application class, e.g. `@ContextConfiguration(classes = SchedulingApplication.class)` with `@WebMvcTest`, or `@SpringBootTest(classes = SchedulingApplication.class)`.

Test names carry the TC-ID they cover, e.g. `@DisplayName("TC-FR4.3c: ...")`.

## Cross-stream contracts

| Interface | Owner | State |
|---|---|---|
| `security.CurrentUserProvider` | A | `get()` returns a `CurrentUser` record (`id`, `displayName`, `role`, `departments`, with `isScheduler()` and `canManage(department)`). Day-1 stub: `StubCurrentUserProvider` |
| `service.NoticeService` | A | takes ids. Booking decisions call `notifyRequester(bookingId, event)` inside their own transaction |
| `service.TimetableOccupancy` | A | implemented (`SectionOccupancy`). Which rooms and equipment units the timetable uses in a time window, so availability search and booking approval leave out rooms with a class in them |
| `service.SchedulePlacement` | A | implemented (`ImportedSchedulePlacement`). Places one imported schedule row in the Draft timetable through `TimetableService`, with the same rules and conflict re-check as a section placed by hand |
| `ConflictService` | B | implemented (`DefaultConflictService`). `recheck` runs after every section change, term copy and date change. **Not yet called** when a booking is approved or cancelled |
| `AvailabilityService` | B | implemented (`DefaultAvailabilityService`). Manages instructor weekly availability blocks, equipment needs, published term locks, and timetable lens overlays |
| `InsightsService` | B | implemented (`DefaultInsightsService`). Computes faculty workload and room occupancy/fill analytics against OI-3 standards, positive confirmations, and deduplication with open conflicts |
| `SisClient` | B | stub only (`StubSisClient`): TD-1 enrollment figures and shared-student counts. The real SIS client is still to come |
| `CatalogService`, `BookingService`, `ImportService` | C | implemented (`*ServiceImpl`) |

Service implementations take the caller from `CurrentUserProvider` (through `Caller`), never from method arguments (NFR1.3, NFR1.4).
`TimetableOccupancy` and `SchedulePlacement` are looked up with `ObjectProvider`, so a test can leave them out and the services still start.

## Signing in

The API is a resource server: it only checks tokens, and never asks for a password or keeps an account. TR4.2 leaves sign-in, and managing accounts, to the Core Platform. The role comes only from the token's `role` claim (`STUDENT`, `INSTRUCTOR` or `SCHEDULER`), and the display name from `name`. **The real Core Platform's claim names are still to be confirmed**; they are constants in `SecurityConfig`.

Who plays the Core Platform depends on the profile:

| Profile | Sign-in | Notes |
|---|---|---|
| `local` (default for `spring-boot:run`) | Test-account picker, `POST /dev/token` behind it | No passwords and no account pages. Data in `./data` |
| `accounts` | `Project/account-service` on port 9000: usernames and passwords | Start it first (see [its README](../account-service/README.md)). **Account** in the sidebar opens its pages: change password, and for administrators, manage accounts. Data in `./data`, the same database as `local` |
| `dev`, `test` | Test-account picker, `POST /dev/token` | Like `local`, with an in-memory database |
| any other | The real Core Platform at `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Refuses to start without it |

With a Core Platform (the `accounts` profile, or a real one), the browser signs in with the OpenID Connect authorization-code flow and PKCE (`js/api.js`, `signin-callback.html`). `GET /auth/config` gives it the sign-in, token and sign-out addresses from the Core Platform's discovery document (`<issuer>/.well-known/openid-configuration`, read by `IssuerDiscovery`), so any standard OpenID Connect provider works and nothing here assumes which product it is. If the Core Platform cannot be reached, `/auth/config` answers 503 and the sign-in page says so.

### What this module reads from a token

| Claim | Used for |
|---|---|
| `sub` | the user id; everything this module stores about a person is keyed on it |
| `role` | `STUDENT`, `INSTRUCTOR` or `SCHEDULER`; any other value grants nothing |
| `name`, `email` | display name and email in the person's scheduling profile |
| `departments` | a Scheduler's departments, a list (TR3.4). Without this claim they come from the `scheduler_department` table, which only the test accounts and test data fill |
| `department` | an instructor's home department |
| `aud` | must contain the configured audience |

A staff member's scheduling profile (`app_user`) is created or updated from these claims on their requests (`ProfileSync`), so an instructor can be assigned once they have signed in. The three test accounts (same ids in every profile) have profiles from the start (`TestAccountsConfig`). A claim the token leaves out keeps the stored value. Students are never stored.

### Test accounts

In the local, dev and test profiles, `POST /dev/token` plays the Core Platform. It needs no token.

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

### A real Core Platform

Any profile other than local, dev and test refuses to start unless two settings are given:

| Setting | Value |
|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | the Core Platform issuer |
| `spring.security.oauth2.resourceserver.jwt.audiences` | the `aud` the Core Platform puts in its access tokens for this API, so tokens issued for other services are refused. Ask the Core Platform team; there is no default. If its tokens carry no `aud`, set `scheduling.auth.require-audience=false` instead |

Three more match how the Core Platform registered this UI, if they differ from the defaults:

| Setting | Default |
|---|---|
| `scheduling.auth.client-id` | `scheduling-web` |
| `scheduling.auth.scope` | `openid profile email` |
| `scheduling.auth.account-uri` | blank, so no **Account** link; the Core Platform's page for changing a password |

The Core Platform must allow the UI's sign-in return address `<this app>/signin-callback.html`, its sign-out return address `<this app>/`, and a cross-origin `POST` to its token endpoint from this app's origin. `application-accounts.yml` is a complete example.

**Adding an endpoint:** give it `@PreAuthorize` and add a row to `SecurityMatrixIT.ENDPOINTS`. That test fails until you do both. In tests that load the full application, use `@ActiveProfiles("test")`.

## REST API: terms and sections (Dev A)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/instructors` | Scheduler | Everyone a section can name as an instructor, as `{"id","name"}`. Roles are not stored, so staff who don't teach are listed too |
| `GET /api/teaching?termId=` | Instructor, Scheduler | The caller's own sections in the term (course title, credit hours, room, all instructors, meetings) and a `workload` total: sections, courses, credit hours and `weeklyContactMinutes` (weekly meetings only). Always the caller from the token. An Instructor gets 404 for a Draft term |
| `GET /api/terms`, `GET /api/terms/{id}` | All | Students see only published terms; a Draft answers 404 for them |
| `POST /api/terms` | Scheduler | 201; always starts as a Draft |
| `PATCH /api/terms/{id}` | Scheduler | body `{"name","startDate","endDate"}`, each optional: a field left out keeps its value, and everything is saved or nothing is. Only a Draft's dates can change (a Published term answers 409), the caller must be authorized for all its sections, and no one-time meeting or exam may end up outside the new dates (422) |
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

## REST API: conflicts (Dev B)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/terms/{id}/conflicts?type=&severity=&status=` | Scheduler | All filters optional. `status` is `OPEN`, `RESOLVED` or `DISMISSED` |
| `POST /api/terms/{id}/rescan` | Scheduler | Runs detection for the whole term and returns its conflicts (FR4.2) |
| `GET /api/terms/{id}/dismissals` | Scheduler | The dismissal audit log of the term's conflicts, newest first: conflict, type, explanation, who, when and why (NFR3.3). 404 for an unknown term |
| `POST /api/conflicts/{id}/reassign` | Scheduler | body `{"sectionId","roomId","instructorIds","meetings"}`, each optional; `sectionId` defaults to the conflict's first section. Changes the section, then re-checks the term |
| `POST /api/conflicts/{id}/dismiss` | Scheduler | body `{"reason":"..."}`, required (400 without it). Writes an audit entry: who, when, which conflict and why (NFR3.3) |

- Detection runs inside the change's own transaction (`ConflictService.recheck`) and never stops a Draft from being saved. Publishing with open High-severity conflicts answers 409 until `confirm=true` (FR4.7).
- The six types and their severity: room double-booking, instructor overlap and instructor unavailable are High; equipment double-booking and capacity exceeded are Medium; student-group clash is Low. An instructor's Prefer blocks never create a conflict.
- Each conflict has a fingerprint. A re-scan updates the existing row instead of adding a duplicate, a conflict whose cause is gone becomes `RESOLVED`, and a `DISMISSED` one stays dismissed.
- Explanations are plain language and name the sections, e.g. "Room double-booking in Founders 110: MATH 210 sec 01 (MONDAY 10:00–11:00) and PHYS 110 sec 01 (MONDAY 10:30–11:30) overlap."
- Known gaps are listed under "Not wired yet" at the end of this file.

## REST API: availability & insights (Dev B)

| Method and path | Roles | Notes |
|---|---|---|
| `GET /api/terms/{id}/availability/me` | Instructor | Current instructor's declared availability blocks, equipment requirements, and notes for the term |
| `PUT /api/terms/{id}/availability/me` | Instructor | Save weekly availability blocks (`OPEN`, `PREFER`, `UNAVAILABLE`) and portable equipment needs. Rejects edits if term is `PUBLISHED` (HTTP 409, NFR3.4). Re-checks term conflicts on save |
| `GET /api/instructors/{id}/availability?termId=` | Scheduler, Instructor | Declared availability lens for timetable scheduling grid. Returns weekly blocks with status and times |
| `GET /api/terms/{id}/insights` | Scheduler | Optimization insights (FR5): faculty workload vs declared open/prefer hours (FR5.1), room occupancy vs 50h weekly standard and room fill percentage (FR5.2), positive confirmations, and deduplication badge (`alreadyFlagged = true`) linking to open conflicts (FR5.4) |
| `POST /api/insights/{id}/dismiss` | Scheduler | Dismiss an advisory insight suggestion without modifying timetable state (FR5.3) |

## Catalog, booking and import: how it works

### Who may do what

| | Scheduler | Instructor | Student |
|---|---|---|---|
| Subjects: read, add | yes | no | no |
| Rooms and equipment: read | yes | yes | no |
| Rooms and equipment: add, change status | yes | no | no |
| Request a room or equipment | yes | yes | no |
| See the booking queue | everything | only their own requests | no |
| Approve or cancel a booking | yes | no | no |
| Search availability | yes | yes | no |
| Import a CSV, download a template | yes | no | no |

The controllers check this with `@PreAuthorize`, and every service method checks it again. A subject's department must be one the Scheduler may manage (`CurrentUser.canManage`). What the user types is first matched to the spelling in their authorized list, so `math` is stored as `MATH`.

### Bookings

A request goes through three states: `PENDING`, then `APPROVED` or `CANCELLED`. Only `APPROVED` bookings reserve a resource.

1. A room or equipment item can be requested only when its status is Available. A booking holds exactly one resource.
2. **Approving** takes a row lock on the room (or equipment item) first, then re-reads the booking, then counts overlapping approved bookings, all in one transaction. Two approvals for the same room therefore run one after the other, and the second gets `BookingCollisionException` (HTTP 409). A room holds one booking at a time. Equipment conflicts only when **all** its units are taken.
3. Two windows overlap only if each starts before the other ends. A booking that ends at 17:00 does not block one that starts at 17:00.
4. **Cancelling** also takes the lock, so it cannot cross with an approval of the same booking. It works on pending and approved bookings.
5. The requester gets a notice in the same transaction: `BOOKING_APPROVED`, `BOOKING_DECLINED` (a pending request was turned down) or `BOOKING_CANCELLED` (an approved booking was withdrawn). A Scheduler who decides their own request gets no notice.

### Availability search

Search offers a room only when all of these hold: it is Available (Maintenance and Checked out are hidden), it has no approved booking in the window, and the timetable does not use it (`TimetableOccupancy`). Optional filters are minimum capacity, room type and fixed equipment. Equipment search shows the **free units**: total, minus approved bookings, minus what sections need.

### Import

`POST /api/import/{type}` with a CSV file in the multipart field `file`. The type is `rooms`, `equipment`, `subject-list` or `ready-made-schedule`.

- **The header is checked first.** It must match the template: same columns, same order, capitals and spaces ignored. A wrong header, an empty file or broken quoting rejects the **whole file** with HTTP 422 and records nothing.
- **Every other row is judged on its own.** The answer is HTTP 200 with a list of rows, each `ADDED` or `NEEDS_REVIEW` with a plain-language reason. Good rows stay even if others fail. Each row is saved in its own transaction. The whole upload is recorded as a batch with its rows.
- **File rules:**
  - A value that contains a comma must be wrapped in double quotes: `"AV Storage, Hale Hall"`. Otherwise the row gets an extra column and fails.
  - Several items in one cell (room equipment, subject equipment, co-instructors) are separated by `;`.
  - Days are `M`, `T`, `W`, `Th`, `F`, written together (`MWF`, `TTh`). Times are 24-hour (`13:00`).
  - Room type is `Lecture hall`, `Lab` or `Seminar room`.
  - The template's example text `(leave blank if none)` counts as empty.
  - Excel's UTF-8 byte-order mark is removed. Uploads over Spring's 1 MB multipart limit fail; raise `spring.servlet.multipart.max-file-size` if you need more.
- **Names are matched ignoring case and spacing** (`core.importing.Keys`), so `Fall2026` finds the term "Fall 2026" and `founders110` finds "Founders 110". The stored value keeps its original spelling.
- **Schedule rows** (`ready-made-schedule`) need the subject to be in the catalog already (import the subject list first). The room must exist (otherwise the reason is `room not recognized`), and each instructor must match exactly one person by display name. The row is then handed to `SchedulePlacement` (Dev A), which finds the term, places the section and checks conflicts at once. A subject that already has a placed section in that term is a duplicate (reason starts with "Duplicate subject code").
- **Templates.** `GET /api/import/templates/{type}` returns the header line. The columns are defined in one place, `core.importing.ImportColumns`. `ImportTemplatesTest` should keep the files in `import-templates/` in step with it.

### How errors reach the client

| HTTP | Thrown | Example |
|---|---|---|
| 400 | bean validation | field messages in the `errors` property |
| 403 | `AccessDeniedException` | a Student calls an endpoint, or a Scheduler adds a subject to another department |
| 404 | `NotFoundException` | unknown room or booking id |
| 409 | `ConflictingStateException`, `BookingCollisionException` | approving a booking that is already approved |
| 422 | `BusinessRuleException` | capacity below 1, end before start, wrong import header |

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
- `domain.Section` still holds one meeting, as in Phase 2, and only `DomainModelTest` uses it. The application's sections are `SectionEntity`, with any number of weekly, one-time and exam meetings, and the conflict rules use `core.MeetingTime`.

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
4. **No test or demo data in migrations.** Migrations also run in production. Put sample data in the dev/test loader (`Td1Loader`, or a `@Profile("dev")` runner). Remember that a Scheduler signed in through `/dev/token` needs a row in `scheduler_department`, or every department check fails.
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
- The booking queries count bookings only. Rooms and equipment that timetable sections use come from `TimetableOccupancy` (`SectionOccupancy`), which `BookingServiceImpl` adds in search and approval.

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

## Tests

`./mvnw verify` runs everything. Names ending `Test` are unit tests, names ending `IT` are integration tests. Test names carry the case id they cover, e.g. `@DisplayName("TC-FR2.3b: ...")`.

| Test | What it checks | Needs Spring? |
|---|---|---|
| `core.importing.*Test` | CSV parsing, header check, row rules, day and time codes | no |
| `service.*ServiceImplTest` | role checks, validation, approval order (lock, refresh, check), notices, import outcomes. Repositories and `NoticeService` are Mockito mocks | no |
| `persistence.EntityMappingTest` | every entity against the real tables (names, NOT NULL, lengths, enum values) | no |
| `support.Td1LoaderTest`, `support.Td2FilesTest` | the data set itself (see below) | no |
| `persistence.PersistenceIT` | repository queries, the row lock, the overlap boundary, the queue fetch | yes |
| `service.BookingConcurrencyIT` | two Schedulers approve overlapping bookings at once: exactly one wins | yes (not `@Transactional`) |

- The tests that need no Spring run in seconds. Keep them that way: put rules in `core` or in a service, not in a controller.
- `PersistenceIT` and `BookingConcurrencyIT` start the whole application. If the context cannot start yet (for example the JWT security config is missing), use a `@DataJpaTest` slice for `PersistenceIT` (its package changed in Spring Boot 4, so use your IDE's import). `BookingConcurrencyIT` calls the service from two threads, so the current-user provider must work from any thread.
- Service tests build their caller with `TestUsers`. If `CurrentUser` changes shape, adapt `Caller` and `TestUsers` only.
- `Td1LoaderTest` and `Td2FilesTest` use `support.TestDatabase`, which applies the real migrations to a throw-away database. They run on H2 by default. Add `-Dtd1.jdbc.url=jdbc:postgresql://host/db -Dtd1.jdbc.user=... -Dtd1.jdbc.password=...` to run them on PostgreSQL instead (the `public` schema is wiped first).

### Test data: TD-1 and TD-2

`support.Td1Loader` puts the database into the known state the test plan asks for (TP3). It is plain SQL on the V1 tables, so it needs no entities and no Spring.

```java
// a @Transactional test: join the test's transaction, everything rolls back afterwards
Td1Loader.resetAndLoad(DataSourceUtils.getConnection(dataSource));

// a test that is not transactional (e.g. a concurrency test): commits by itself
Td1Loader.resetAndLoad(dataSource);
```

- `reset` empties **every** application table in foreign-key order. When a migration adds a table, add it to `Td1Loader.TABLES_CHILD_FIRST`; `Td1LoaderTest` fails until you do.
- `support.Td1` holds the ids (`Td1.MATH_210`, `Td1.CARTER_CENTER_301`, ...) and `Td1.ENROLLED`, the enrollment numbers that the SIS stub must return. Enrollment is not stored in the database.
- Conflicts are **detected**, never pre-loaded.

What TD-1 contains: term Fall 2026 (Draft), 7 sections, 5 rooms, 4 equipment types (two laptop carts), the TP3 accounts, instructor availability, and two past bookings. It has exactly three known conflicts:

| Conflict | Data |
|---|---|
| Room double-booking | CS 340 and ART 220, Innovation Lab B, Monday 13:00-14:15 (TC-FR4.3a) |
| Instructor unavailable | CS 340 while Dr. Novak is unavailable Monday 12:00-14:00 (TC-FR4.3c) |
| Capacity exceeded | ENG 101 in Carter Center 301 (seats 80, the SIS stub reports 92) (TC-FR4.3e) |

Other facts the tests rely on: MATH 210 and PHYS 110 overlap on Tuesday in different rooms with different instructors (TC-FR4.3f); instructor load is Bianchi 6 of 18 hours, Novak 2 of 8, Alvarez 9 of 10 (TC-FR5.1); Founders 110 is underused, Hale Hall 204 oversized and Innovation Lab B well used (TC-FR5.2). Maya Brooks manages every department the test steps touch, not just CS and MATH.

TD-2 is in `src/test/resources/td2/`: the four filled templates, `schedule-lab-b-monday.csv` (TC-FR3.7) and `schedule-faulty.csv` (TC-FR3.6: one valid row, a duplicate subject code, a missing field, an unknown room). The test plan runs the imports in order: `subjects.csv` first, so that `schedule.csv` can place its subjects.

## Not wired yet

- Approving or cancelling a booking does not call `ConflictService.recheck` yet, so its conflicts only change on the next section edit or re-scan (Dev B, in Dev C's `BookingServiceImpl`).
- A lock timeout during approval is not mapped to a friendly 409 yet: add a handler for `PessimisticLockingFailureException` in `ApiExceptionHandler`.
- The SIS is a stub, and its student-group counts are keyed by course code and TD-1 section ids, so a student-group clash is not found between sections created through the API. (Instructor availability, timetable overlay lens, and optimization insights are fully implemented and wired).

Spring needs to find the entities and repositories in `persistence`. `SchedulingApplication` lives in `app`, so it must contain `@EntityScan(basePackages = "persistence")` and `@EnableJpaRepositories(basePackages = "persistence")`. Check the `@EntityScan` import with your IDE, because Spring Boot 4 moved it.