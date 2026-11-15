# Scheduling Test Suite Overview

This folder contains the automated test suite for the scheduling application. The tests cover the domain model, persistence layer, business services, API endpoints, security rules, schema checks, and import workflow. They are written in JUnit 5 and run with the `test` Spring profile, using the in-memory H2 configuration defined in `resources/application-test.yml`.

## Test configuration

- `resources/application-test.yml`
  - Sets the Spring test profile to use an in-memory H2 database in PostgreSQL compatibility mode.
  - Ensures tests run against a clean, repeatable database without the production setup.
  - Contains the base timezone and dev-auth settings used by the application during testing.

## Test data resources

These files provide fixed datasets used by the test suite.

- `resources/td1/td1.sql`
  - Baseline dataset for TD-1.
  - Seeds a Fall 2026 term, users, rooms, equipment, subjects, sections, instructor availability, and known conflicts.
  - Used to assert that the conflict engine detects exactly the expected problems.

- `resources/td2/subjects.csv`
  - Valid subject import template with example department and course records.
  - Verifies subject import ordering and catalog creation.

- `resources/td2/rooms.csv`
  - Sample room import file with building and capacity data.
  - Confirms room imports are valid and do not collide with TD-1 identifiers.

- `resources/td2/equipment.csv`
  - Sample equipment import file.
  - Verifies equipment names, locations, and item types parse correctly.

- `resources/td2/schedule.csv`
  - Valid ready-made schedule file used to check that a schedule can be imported without new conflicts.

- `resources/td2/schedule-lab-b-monday.csv`
  - Variant schedule that intentionally creates a room double-booking in Innovation Lab B.
  - Used to validate conflict detection for a specific lab/room scenario.

- `resources/td2/schedule-faulty.csv`
  - Faulty sample schedule used to ensure the parser rejects malformed or invalid import rows.

## Test classes and what they validate

### API tests

- `api/AvailabilityApiIT.java`
  - Tests the instructor availability API.
  - Verifies that instructors can read and submit availability, that published terms reject updates, and that schedulers can request the availability lens overlay.

- `api/CalendarApiIT.java`
  - Exercises the calendar API and the calendar export/filter behavior.
  - Covers term view generation, weekly calendars, filters, and published-term edge cases.

- `api/ConflictApiIT.java`
  - Validates conflict-related API behavior.
  - Checks room clashes, dismissal workflows, and the conflict log/audit trail.

- `api/InsightsApiIT.java`
  - Tests the insights API for utilization and scheduling analytics.
  - Confirms the system returns the expected summary and metric data to schedulers.

- `api/NoticeApiIT.java`
  - Verifies notice retrieval and mark-as-read behavior.
  - Covers the API contract for user-facing notices and alert actions.

- `api/ScheduleImportIT.java`
  - Tests schedule import through the application API.
  - Confirms valid imports are accepted and invalid data is rejected with the correct response patterns.

- `api/TimetableApiIT.java`
  - Covers timetable generation and timetable-related API endpoints.
  - Validates the scheduling view that combines sections, rooms, and instructor data.

- `api/TimetableOccupancyIT.java`
  - Tests occupancy and timetable availability queries.
  - Checks that conflicts or room occupancy limitations are reflected in API responses.

- `api/error/ApiExceptionHandlerTest.java`
  - Verifies that API exceptions are translated into consistent client-facing error responses.
  - Ensures validation failures and not-found cases return useful messages.

### Application bootstrap tests

- `app/ImportTemplatesTest.java`
  - Validates the import template resources that the application exposes to users.
  - Ensures the expected template files and headers are present and usable.

- `app/SchedulingApplicationTests.java`
  - Smoke test for the application context.
  - Confirms the Spring application loads successfully in the test profile.

### Core domain/business-rule tests

- `core/CalendarDatesTest.java`
  - Tests date and calendar computations used by the scheduling logic.
  - Verifies boundaries such as week ranges, day calculations, and term date handling.

- `core/IcsWriterTest.java`
  - Verifies the ICS export writer creates valid calendar payloads.
  - Checks formatting, dates, and event serialization.

- `core/MeetingTimeTest.java`
  - Tests time-interval logic used throughout scheduling.
  - Validates overlap, ordering, and time-bound checks.

- `core/NoticeMessagesTest.java`
  - Ensures notice and user-message content is built correctly.
  - Tests text generation for conflict and scheduling notices.

- `core/conflict/ConflictRulesTest.java`
  - Checks the conflict detection rules.
  - Covers room double-booking, instructor unavailability, capacity exceedance, and other scheduling conflicts.

- `core/importing/CsvParserTest.java`
  - Tests CSV parsing behavior.
  - Validates quoting, BOM handling, newline handling, and malformed-row rejection.

- `core/importing/ImportFileParserTest.java`
  - Verifies the import parser converts raw CSV rows into typed domain objects.
  - Ensures validation errors are surfaced correctly.

- `core/insights/UtilizationCalculatorTest.java`
  - Tests utilization and occupancy calculations used in insights and analytics.
  - Validates summarized department or resource usage numbers.

### Domain model tests

- `domain/DomainModelTest.java`
  - JUnit 5 version of the Phase 2 domain verification tests.
  - Covers actors/roles, section placement, overlap rules, bookings, conflict dismissal, term copy/publish behavior, and room validation.

### Integration tests

- `integration/StubSisClientTest.java`
  - Verifies the SIS stub client behaves like a realistic enrollment data source.
  - Tests data shape, student counts, and enrollment lookup behavior.

### Persistence tests

- `persistence/EntityMappingTest.java`
  - Confirms JPA/entity mapping works as expected.
  - Checks entity relationships, field mapping, and persistence configuration.

- `persistence/PersistenceIT.java`
  - End-to-end persistence test against the application database.
  - Validates that repositories and entities store and retrieve the expected data.

### Schema and race-condition tests

- `schema/ApprovalRaceCheck.java`
  - Checks for race conditions or concurrent approval issues in booking or conflict approval workflows.

- `schema/SchemaCheck.java`
  - Validates the database schema against the expected structure.
  - Ensures persistence contracts and table mappings stay correct.

### Security tests

- `security/DevTokenClockIT.java`
  - Validates the dev token flow and clock behavior used in local or test authentication.

- `security/IssuerDiscoveryTest.java`
  - Verifies the issuer discovery and OpenID metadata behavior used by the app.

- `security/JwtCurrentUserProviderTest.java`
  - Tests JWT parsing and how the current authenticated user is resolved from tokens.

- `security/ProfileGuardTest.java`
  - Checks profile-based access control and guard logic.
  - Ensures only allowed roles or user states can access protected actions.

- `security/ProfileSyncTest.java`
  - Verifies user profile synchronization and role/profile updates.

- `security/SecurityMatrixIT.java`
  - Large authorization matrix test that checks every endpoint against allowed roles.
  - This is the main regression guard for access control across the whole API.

- `security/StaticUiIT.java`
  - Tests static UI pages or the dev front-end endpoints under security constraints.

### Service tests

- `service/BookingConcurrencyIT.java`
  - Tests concurrent booking operations to expose race conditions and double-booking errors.

- `service/BookingServiceImplTest.java`
  - Unit test for booking workflow logic.
  - Covers room/equipment requests, queue visibility, approval/cancellation flows, validation errors, and authorization rules.

- `service/CatalogServiceImplTest.java`
  - Validates catalog operations such as adding rooms, equipment, and subjects.
  - Tests validation, duplicate detection, permissions, and status updates.

- `service/ImportServiceImplTest.java`
  - Tests the import service that processes imported schedule and catalog files.
  - Ensures valid data enters the system correctly and invalid rows are rejected.

### Support and fixture tests

The `support` package is not a product feature itself, but it contains reusable test fixtures and verification helpers.

- `support/Td1LoaderTest.java`
  - Checks the TD-1 fixture loader loads the correct baseline dataset and records.

- `support/Td2FilesTest.java`
  - Validates the TD-2 CSV fixture files against the parser and the known dataset constraints.
  - Ensures schedule and subject imports stay compatible with the baseline data.

- `support/FixedClock.java`
  - Test clock utility used to freeze time in service-level logic checks.

- `support/ScheduleModel.java`
  - Helper model for comparing schedule rows and conflict detection outcomes.

- `support/TestDatabase.java`
  - Utility to create a known test database state.

- `support/Td1.java`
  - Holds the known TD-1 dataset, including expected enrollment and conflict metadata.

- `support/Td1Loader.java`
  - Loads the TD-1 SQL seed data used by integration tests.

## Summary

This suite is designed to verify the scheduling application's correctness from the business rules down to the HTTP API and database layer. The most important coverage areas are:

- domain correctness and scheduling logic;
- conflict detection and dismissal rules;
- access control and endpoint authorization;
- import parsing and template validation;
- persistence and schema integrity;
- concurrent booking behavior and race safety.

Together, these tests provide a strong regression net for the academic scheduling system.
