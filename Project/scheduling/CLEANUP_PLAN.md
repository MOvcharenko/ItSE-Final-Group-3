# Project review and cleanup plan: Academic Scheduling (`Project/scheduling`)

## Context

You asked for a full review: is the project DRY, SOLID, well structured and clear, and where is it overengineered or
carrying code it doesn't need. I read all production Java (~11k lines), re-checked the frontend (~3.4k lines of JS)
against the existing `ARCHITECTURE_REVIEW_RESULTS.md`, and checked every "unused" claim by search.

You chose: **carry out all cleanup tiers**, but for three topics **write analysis only, no code changes**: the Phase 2
domain classes, the single-implementation service interfaces, and sign-in for a production product. Those go into a new
review document, `Project/scheduling/PROJECT_REVIEW.md`, written last so it also records what was changed.

## Verdict (summary for the review document)

- **Structure is good:** clear layers (`api` → `service` → `core`/`persistence`), thin controllers, entities never leave
  services, framework-free `core`, one injected `Clock`, security checked twice. `TimetableService`, `CalendarService`,
  `PersistentNoticeService`, `BookingServiceImpl`, `ImportServiceImpl`, `core.importing` and `security` are clean.
- **Quality is uneven:** the conflict, availability and insights code (`DefaultConflictService`, `DefaultInsightsService`,
  `DefaultAvailabilityService`, `ConflictRules`) has long methods with numbered comments, N+1 lookups, copied blocks and
  one endpoint that bypasses the timetable's rules.
- **Overengineering** is mostly leftover scaffolding from parallel team work: a parallel Phase 2 model, "cross-stream
  contract" interfaces, `ObjectProvider` fallbacks for beans that now always exist, "Dev A/B/C" comments, test fixtures
  inside the production SIS stub, and three sign-in mechanisms.

## Step 0 — Save this plan in the project, then pause

Write this plan to `Project/scheduling/CLEANUP_PLAN.md` (next to `ARCHITECTURE_REVIEW_RESULTS.md`) and stop. No code
changes until you have read the file and said to continue with Step 1.

## Step 1 — Bugs and integrity

1. **Delete `POST /api/conflicts/{id}/reassign`.** `DefaultConflictService.reassign` (service/DefaultConflictService.java:323)
   edits a section with no term lock, no department check, no room-status check, no instructor/meeting validation, and
   notifies even when nothing changed. The UI uses `PATCH /api/sections/{id}/assignment` (`TimetableService.changeAssignment`)
   instead. Remove `ConflictService.reassign`, `ConflictController.reassign`, `api/dto/ReassignRequest.java`, its row in
   `SecurityMatrixIT.ENDPOINTS`, and the README row.
2. **Stale insight cards never disappear.** `DefaultInsightsService.getInsights` never removes a non-dismissed suggestion
   whose instructor/room no longer has that verdict. Delete those during the sync.
3. **`copyTerm` re-checks the whole term once per copied section** (service/TimetableService.java:226). Call
   `conflicts.recheck` once, after the loop.
4. **Availability lens open to every Instructor** (`GET /api/instructors/{id}/availability`). Only the Scheduler's
   timetable uses it: restrict controller + service to Scheduler; update `SecurityMatrixIT` and README.

## Step 2 — Remove what isn't needed (except the Phase 2 classes, which are analysis-only)

1. **Dead members:** `ConflictService.Ref` and `recheck`'s unused `changed` parameter (→ `recheck(String termId)`);
   `ConflictEntity.setResolutionState/setDetectedAt/setResolvedAt/addSectionId`; `ConflictRules.getStudentGroupThreshold`;
   `InstructorAvailabilityBlock.isPrefer`; `ScheduledBooking.status`/`isApproved` (only approved bookings are passed);
   any `MeetingTime` overloads only tests use.
2. **Fallbacks for beans that always exist:** `TimetableOccupancy.none()` + `ObjectProvider<TimetableOccupancy>` in
   `BookingServiceImpl`; `ObjectProvider<SchedulePlacement>` + the "not available yet" branch in `ImportServiceImpl`.
   Inject directly (interfaces themselves stay).
3. **Throw-away harnesses:** `src/test/java/schema/SchemaCheck.java`, `src/test/java/schema/ApprovalRaceCheck.java`,
   `run-schema-check.sh` (self-described throw-away; covered by `EntityMappingTest`, `TestDatabase`, `PersistenceIT`,
   `BookingConcurrencyIT`), and `db/migration/.gitkeep`.
4. **Unused API fields:** `AvailabilityView.Summary` (UI totals hours itself in `teaching.js`), `ConflictView.fingerprint`,
   `bookingId`, `equipmentName` — only where no UI code or test reads them.
5. **Team-process comments:** replace "Dev A/B/C", "owner:", "cross-stream contract", "Until a bean exists…" with what the
   code does (`Caller`, `TestUsers`, `SchedulePlacement`, `NoticeService`, `ConflictService`, `CurrentUserProvider`,
   `StubSisClient`, controllers).

## Step 3 — Simplify

1. **SIS client.** Reduce `SisClient` to `enrolledStudentIds(sectionId)`, `enrolledSectionIds(studentId)`,
   `enrollmentCount(sectionId)`, `sharedStudents(a, b)`; failure = `SisUnavailableException` (drop `isAvailable()` and the
   course-code overload). `StubSisClient` keys enrollment by course code once and resolves section id → course code via
   its `SectionRepository`, so callers lose their "by id, else by course code" fallbacks. Keep its setters only if tests use them.
2. **`DefaultConflictService.recheck` (175 lines)** → `snapshot(term)` + `reconcile(term, detected)`; load users and
   equipment once into maps, query approved bookings instead of `findAll()` + filter, drop the equipment-name keys (and the
   matching `equip.name()` checks in `ConflictRules`), drop blank-id guards.
3. **`ConflictRules`:** one sorted-pair helper for the three fingerprints; drop null-list guards in `evaluate`.
4. **One meeting type:** move `overlaps`, `overlapDuration`, `formatWindow` from `core.MeetingTime` onto `domain.MeetingSlot`
   (or a static helper in `core`), delete `MeetingTime`, adapt `MeetingTimeTest`/`ConflictRulesTest`.
5. **One way to read the current user:** move `isInstructor()`, `requireScheduler()`, `requireStaff()`,
   `canonicalDepartment()` onto `security.CurrentUser`; delete `service.Caller`; every service injects `CurrentUserProvider`;
   `TestUsers.caller(...)` → `TestUsers.provider(...)`.
6. **Service must not import `api`:** move `AvailabilitySubmissionRequest` to `service.AvailabilitySubmission` (as
   `SectionPlacement` already does).
7. **`DefaultInsightsService.getInsights` (200 lines):** shared `enrollment(section)` helper, one `upsertSuggestion(...)`
   for the three copied blocks, reuse loaded user/room maps, pass `AvailabilityStatus` (not `status.name()`) to
   `UtilizationCalculator.DeclaredBlockSnapshot`.
8. **Insight thresholds defined once:** keep `Thresholds.defaults()` or a `@ConfigurationProperties` record, not both.

## Step 4 — Small DRY and consistency fixes

- One `instructorNames(List<SectionEntity>)` (now identical in `TimetableService` and `CalendarService`).
- `NotFoundException.of(what, id)` everywhere; `term.isPublished()` everywhere.
- `ImportServiceImpl.importFile`: build row views once (`Check.limit` is applied twice).
- Fix stale Javadoc: `TermNameRequest` (only the copy body), `SchedulePlacement` ("until a bean exists").

## Step 5 — Frontend (`src/main/resources/static/js`)

Already fixed since the earlier review: ISSUE-01, ISSUE-07, ISSUE-08. Do:
- `terms.js`: `termLabel`, `sortNewestFirst`, `initialTerm` (now `defaultTerm` in `calendar.js`) and one `termPicker`,
  used by calendar, conflicts, insights, teaching, timetable.
- `labels.js`: `plural`, `meetingLabel` (from `timetable.js`), `ROOM_TYPES`/`roomTypeLabel` (from `catalog.js`), `DAYS`;
  move `layoutOverlaps` beside the grid. Result: no screen imports another screen.
- `latestOnly(loader)` in `api.js` replacing the seven `requestNumber` guards, and applied to Timetable's "Show busy
  times for" lens (unguarded today).
- Not doing ISSUE-03's `SchedulingApi` class or ISSUE-06's five-part split: no JS tests, no build step, little payoff.

## Step 6 — README

Remove the repeated error table, import rules and 1 MB note; remove stale `StubCurrentUserProvider`, `@EntityScan`,
`@EnableJpaRepositories` text (the app uses `@AutoConfigurationPackage`); drop per-developer ownership; reflect Steps 1–5.

## Step 7 — Write `Project/scheduling/PROJECT_REVIEW.md` (for the team)

Sections: verdict; what was changed (Steps 1–6, with file links); what is good and should stay; then three analysis
sections with **no code changes**:

**A. Phase 2 domain classes.** Table of the 18 classes (`User`, `Scheduler`, `Instructor`, `Student`, `Term`, `Section`,
`Room`, `Equipment`, `Subject`, `Booking`, `Conflict`, `AuditLogEntry`, `Notice`, `ImportBatch`, `ImportRow`,
`InsightSuggestion`, `InstructorAvailability`): what each models, its production counterpart (e.g. `Term` →
`persistence.TermEntity`, `Section` → `SectionEntity` + `MeetingSlot`), and where it is used — only `DomainModelTest` and
each other; no production import. Which `domain` types production does use (the enums, `MeetingSlot`, `EquipmentNeed`).
Verdict: not needed at runtime; safe to delete together with `DomainModelTest` (nothing else breaks; Phase 2 stays in
`Project/Phase 2/`). Alternative if grading needs them: move to a clearly named `phase2` test package.

**B. Single-implementation service interfaces.** Table: interface → implementation → callers → test doubles found today
(`NoticeService`, `TimetableOccupancy`, `SchedulePlacement`, `CatalogService` are Mockito-mocked in unit tests;
`BookingService`/`CatalogService`/`ImportService` in `ProfileGuardTest`; `ConflictService`, `AvailabilityService`,
`InsightsService` have none). Note Mockito can mock classes, so tests don't require the interfaces. Note the three naming
styles vs. `TimetableService`/`CalendarService` with none. Verdict per interface: keep `SisClient` and `CurrentUserProvider`
(real seams with a stub/JWT impl); the rest were team-boundary contracts and can become concrete classes with plain names.
Options: (1) remove and rename, (2) keep but name implementations uniformly; recommend (1).

**C. Sign-in for a production product.** Recommendations:
1. Production runs only as a resource server against the institution's identity provider (the existing "any other
   profile" path; no code change). The `local` built-in sign-in is a demo stand-in — in-memory authorization store,
   signing key in `./data`, H2 file DB, no login throttling/lockout, password recovery or MFA — and should not be the
   production IdP; if a self-hosted one is needed, use a dedicated IdP (e.g. Keycloak) and point `issuer-uri` at it.
2. **Provisioning gap (blocker):** outside `local`/`dev`, nothing writes `scheduler_department`, so every Scheduler has no
   departments and `TimetableService.requireEditor` refuses all edits; instructors get an `app_user` row only after they
   use bookings or availability, so they can't be assigned or matched by schedule import before that. Needs departments
   from a token claim or an admin/sync source, and just-in-time profile creation on sign-in.
3. Confirm claim names (`role`, `name`) with the Core Platform; consider multiple roles per person.
4. Browser token: keep `sessionStorage` but add a Content-Security-Policy header (none today); plan for expiry (no refresh
   today); mention the server-side session (BFF) pattern as the stronger option.
5. Keep the dev token picker for tests (profile-guarded); define the demo accounts once (now in both `DevAccount` and
   `application-local.yml`); guard so `dev`/`local` can't start against a non-H2 database.
6. Deployment basics: HTTPS only, audience check on, PostgreSQL, short token lifetime.

## Verification

- `cd Project/scheduling && ./mvnw verify` after each step (unit + integration, incl. `SecurityMatrixIT`,
  `ConflictApiIT`, `InsightsApiIT`, `AvailabilityApiIT`, `TimetableApiIT`, `EntityMappingTest`, `ConflictRulesTest`).
- New tests: insight card removed when a room/instructor recovers (`InsightsApiIT`); Instructor gets 403 on the
  availability lens (`SecurityMatrixIT`); copying a term still produces the expected conflicts (`TimetableApiIT`/`ConflictApiIT`).
- Run `./mvnw spring-boot:run`, sign in as `m.brooks`, click through Timetable (copy term, drag, busy-times lens),
  Conflicts (Re-scan, Reassign, Dismiss), Insights, Catalog import, Bookings; as `l.bianchi`, My Teaching. Check the
  browser console for errors.
- `git grep -n "Dev [ABC]\|ObjectProvider\|\bCaller\b\|MeetingTime" -- src/main/java` and `git grep -n "api\.dto" -- src/main/java/service`
  come back empty.
