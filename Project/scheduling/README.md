# Academic Scheduling System

## Team 3 — SDT 213

This project implements an academic scheduling application for a university context. It supports term planning, room and equipment management, instructor availability, scheduling conflicts, booking requests, and import workflows. The module is located in `Project/scheduling/` and is intended to satisfy the requirements captured in the Phase 1 report and the prototype work from Phase 2.

---

## Project context

The project is organized around a Spring Boot application that exposes a REST API and a lightweight browser-based UI. It sits alongside supporting materials from earlier phases of the course:

| Location | Purpose |
|---|---|
| `Project/Phase 1/` | Requirements and acceptance criteria used as the source of test IDs and functional expectations |
| `Project/Phase 2/` | Original domain model and prototype classes retained as the recorded design baseline |
| `Project/UI Mockup/` | Reference design for the static UI |
| `Project/account-service/` | Optional authentication service used as the Core Platform for sign-in and account management |
| `Project/scheduling/` | Main application module being implemented and evaluated |

This README focuses on the scheduling system itself, rather than the design artifacts that precede it.

---

## What this system does

The application is structured around three user roles:

- Scheduler: manages terms, sections, catalog data, and booking approvals
- Instructor: views assigned teaching, updates availability, and manages personal teaching information
- Student: sees published academic schedules and is limited to read-only access to public information

### Core features

- Scheduling term creation, editing, copy, and publication
- Section creation with conflict detection and conflict review tools
- Instructor availability and preferred/unavailable time blocks
- Room and equipment catalog management
- Booking requests for equipment and rooms
- CSV import for catalog data and ready-made schedules
- Optimization insights and room/workload analysis
- Calendar export in iCalendar format
- Authentication through an external Core Platform or local test-account mode

---

## Architecture summary

The application follows a layered architecture and separates presentation, business logic, persistence, integration, and security concerns.

| Package | Responsibility |
|---|---|
| `api` | Controllers and REST request/response handling |
| `service` | Business logic, transaction control, validation, and authorization checks |
| `core` | Domain rules and import logic without framework dependencies |
| `persistence` | JPA entities, repositories, and database mappings |
| `integration` | External integrations such as stubbed SIS access |
| `security` | JWT handling and current-user access |
| `app` | Application entry point and component scanning |
| `domain` | Shared domain model and enumerations ported from Phase 2 |

Important development conventions from the project:

- Services never return JPA entities directly; they map to view records instead.
- Business rules remain in the `core` layer and are intentionally free of Spring/JPA dependencies.
- Access control is enforced through `@PreAuthorize` and again in services.
- Time is injected via `java.time.Clock` instead of calling `LocalDateTime.now()` in business logic.
- Test names include requirement IDs such as `TC-FR4.3c` to trace behavior back to the specification.

---

## Running the project

### Prerequisites

- JDK 21 or newer
- Use the project wrapper instead of a global Maven installation

### Build and test

From the scheduling module directory:

```sh
cd Project/scheduling
./mvnw verify
```

This runs the project's unit and integration tests.

### Start the application

```sh
cd Project/scheduling
./mvnw spring-boot:run
```

This starts the app using the default local profile. The system is then available in the browser at:

- `http://localhost:8080/`

### Alternative profiles

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=accounts
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
./mvnw spring-boot:run -Dspring-boot.run.profiles=test
```

Profile behavior:

| Profile | Purpose |
|---|---|
| `local` | Default app mode with built-in test-account login |
| `accounts` | Connects to the separate `Project/account-service` for account-based sign-in |
| `dev` | In-memory database mode for lighter API development work |
| `test` | Test-oriented profile used by automated checks |

For full command examples and Windows-specific instructions, see [commands.md](commands.md).

---

## User authentication and role model

The API is designed as a resource server. It validates JWT tokens but does not manage accounts directly. Instead, user identities are supplied by a separate Core Platform. This separation keeps the scheduling module focused on schedule logic rather than local account management.

### Supported role values

- `STUDENT`
- `INSTRUCTOR`
- `SCHEDULER`

### Local development accounts

When running in local or dev-style profiles, the application provides a test-account picker and a dev token endpoint. This allows quick login without external identity infrastructure.

| User ID | Name | Role |
|---|---|---|
| `U01` | Maya Brooks | Scheduler |
| `U02` | L. Bianchi | Instructor |
| `U03` | Jordan Ellis | Student |

When using the external Core Platform profile, the application integrates with OIDC-compatible authentication as configured by the deployment environment.

---

## Main functional areas

### 1. Terms and sections

Schedulers can create academic terms, edit their basic metadata, copy existing terms, and publish them once conflicts are resolved or explicitly confirmed. Sections can be created, assigned to instructors and rooms, and tracked through conflict detection workflows.

Key rules:

- Draft terms can be modified.
- Published terms are locked against destructive edits.
- Publishing with unresolved high-severity conflicts requires explicit confirmation.
- The system detects conflicts such as room overbooking, instructor overlap, instructor unavailability, capacity exceedance, and student-group clashes.

### 2. Availability and workload

Instructors can declare weekly availability using open, preferred, and unavailable time blocks. The scheduler can view these blocks as an overlay on the timetable and use them to inform course allocation decisions.

The system also calculates workload summaries, including:

- assigned sections
- hours taught
- course counts
- credit totals
- weekly contact minutes

### 3. Catalog, rooms, and equipment

The module supports a catalog of subjects, rooms, and equipment. Schedulers manage these resources, while instructors may view rooms or request resource access depending on operational rules.

The booking system enforces a careful approval flow so that only one approved booking may reserve a resource at one time. Approved bookings are treated as actual reservations; pending requests do not block use.

### 4. Importing data

The system supports CSV import for:

- room data
- equipment data
- subject lists
- ready-made schedules

The importer validates file structure, header correctness, and row-level data quality. Valid rows are processed individually, while invalid rows are flagged with clear reasons and recorded without stopping the whole upload.

### 5. Insights and reporting

Schedulers can review optimization insights based on:

- room occupancy and fill rates
- workload balancing across faculty
- positive confirmations
- deduplicated conflict recommendations
- overall timetable quality signals

This area provides decision-support rather than hard-coded scheduling enforcement.

### 6. Calendar export

The application can export a term as an iCalendar file using the same filtering logic as the timetable views. This allows users to import the term into external calendar tools for review and planning.

---

## Static UI and frontend structure

The web front end is implemented as plain HTML, CSS, and JavaScript modules without a build step or framework. Key browser-side files are located under `src/main/resources/static`.

| File | Purpose |
|---|---|
| `js/app.js` | Startup and role-based navigation (which role sees which screen) |
| `js/shell.js` | Page frames: the sign-in landing page and the sidebar/top bar around every screen |
| `js/scheduling-api.js` | Every call to the REST API, named for what it does; the only file that knows the URLs |
| `js/http.js` | Transport: adds the token, turns errors into plain-language messages, returns to sign-in on a 401 |
| `js/auth.js` | Signing in and out: test accounts and the OpenID Connect flow with PKCE |
| `js/session.js` | What the browser tab remembers: token, chosen term, section to open next |
| `js/terms.js` | The shared term picker and the rule for which term a screen opens with |
| `js/labels.js` | Shared wording: plurals, days, room types, meeting labels |
| `js/layout.js` | Places overlapping events side by side in both time grids |
| `js/dom.js` | DOM helper functions and the shared table builder |
| `js/dates.js` | Local date formatting and time handling |
| `js/forms.js` | Reusable form behaviors and validation messaging |
| `js/calendar.js` | Calendar views and notifications |
| `js/timetable.js` | Term and section scheduling workflows |
| `js/conflicts.js` | Conflict review and resolution tools |
| `js/insights.js` | Optimization insights dashboard |
| `js/catalog.js` | Resource and catalog management |
| `js/import.js` | Import UI and CSV processing |
| `js/bookings.js` | Booking requests and decisions |
| `js/search.js` | Availability search |
| `js/teaching.js` | Instructor teaching dashboard |

The UI was designed to match the mockup in `Project/UI Mockup/academic-scheduling-mockup.html` and is intentionally lightweight enough to be easy to follow during review.

---

## Security and authorization notes

The system enforces authorization at both controller and service layers. The role of the currently signed-in user is derived from the token, and the application uses that role to decide which screens and endpoints are accessible.

Some important project rules:

- The scheduling module never stores local passwords or personal account credentials.
- Access to sensitive endpoints is restricted by `@PreAuthorize`.
- The current user is always read through the security abstraction rather than passed in directly from the client.
- The Core Platform determines the user identity and claims; the scheduling module interprets those claims.

The project includes a security matrix test to ensure endpoints have expected access policies.

---

## Testing strategy

The project uses a layered testing strategy with both fast unit tests and full application tests.

| Test type | Purpose |
|---|---|
| `*Test` | Unit tests for parsing, validation, and service logic |
| `*IT` | Integration tests for broader app and repository behavior |
| `EntityMappingTest` | Validates persistence mappings and database schema alignment |
| `Td1LoaderTest` / `Td2FilesTest` | Verifies expected test data fixtures and import scenarios |
| `BookingConcurrencyIT` | Confirms concurrent booking approvals remain safe |

The standard validation step is:

```sh
./mvnw verify
```

This is the main verification command for the project and should be used before a final review or delivery.

---

## Known gaps and future work

The project is functionally rich, but a few items remain intentionally listed as not fully wired yet:

- Booking approval and cancellation do not yet trigger a full conflict re-check immediately in all cases.
- A lock timeout during booking approval is not yet mapped to a user-friendly 409 response.
- The SIS layer is still a stub, so some student-group logic depends on limited known data.
- The real Core Platform integration remains configurable rather than hard-coded.

These are documented in the project as known limitations, and they are useful to understand during review because they indicate the boundary between completed functionality and planned extension work.

---

## Final note

This module demonstrates a complete, layered software design for an academic scheduling system: user roles, domain logic, persistence, conflict handling, import workflows, and a usable UI. The project is designed to be reviewed both from a functional perspective and from a software engineering perspective, with clear separation of concerns and explicit traceability back to the original requirements.

For the detailed developer workflow, build commands, and environment-specific configuration, refer to [commands.md](commands.md) and the separate [account-service/README.md](../account-service/README.md).