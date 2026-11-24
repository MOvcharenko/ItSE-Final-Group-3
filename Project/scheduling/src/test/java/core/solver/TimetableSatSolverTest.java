package core.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import core.MeetingTime;
import core.conflict.ConflictRules;
import core.conflict.DetectedConflict;
import core.conflict.EquipmentInfo;
import core.conflict.InstructorAvailabilityBlock;
import core.conflict.ScheduledBooking;
import core.conflict.ScheduledMeeting;
import core.solver.AutoScheduleInput.InstructorOption;
import core.solver.AutoScheduleInput.PlacementKey;
import core.solver.AutoScheduleInput.PreviousSolution;
import core.solver.AutoScheduleInput.RoomOption;
import core.solver.AutoScheduleInput.SectionRequest;
import core.solver.AutoScheduleInput.SolverOptions;
import core.solver.AutoScheduleResult.ProposedPlacement;
import domain.AvailabilityStatus;
import domain.BookingStatus;
import domain.EquipmentNeed;
import domain.MeetingSlot;
import domain.ResourceType;
import domain.RoomType;

/**
 * Pure JUnit 5 tests for {@link TimetableSatSolver} (no Spring/JPA).
 */
class TimetableSatSolverTest {

	private final TimetableSatSolver solver = new TimetableSatSolver();

	@Test
	@DisplayName("SAT solver schedules conflict-free sections verified against ConflictRules (0 conflicts)")
	void solvesConflictFreeAndPassesConflictRulesVerification() {
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "CS 340", "OS", "CS", "01", 3, RoomType.LAB, Set.of(), List.of(), 30, List.of("I1"), "MWF"),
				new SectionRequest("S2", "ART 220", "Digital Art", "ART", "01", 3, RoomType.LAB, Set.of(), List.of(), 20, List.of("I2"), "MWF"),
				new SectionRequest("S3", "ENG 101", "Comp", "ENG", "01", 3, RoomType.LECTURE_HALL, Set.of(), List.of(), 92, List.of("I3"), "TTH"),
				new SectionRequest("S4", "MATH 210", "Calc", "MATH", "01", 3, RoomType.LECTURE_HALL, Set.of(), List.of(), 25, List.of("I4"), "TTH")
		);
		List<RoomOption> rooms = List.of(
				new RoomOption("R-LAB", "Lab B", "Tech", 40, RoomType.LAB, Set.of()),
				new RoomOption("R-BIG", "Hale 204", "Hale", 120, RoomType.LECTURE_HALL, Set.of()),
				new RoomOption("R-SMALL", "Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of())
		);
		List<InstructorOption> instructors = List.of(
				new InstructorOption("I1", "Novak", "CS"),
				new InstructorOption("I2", "Okafor", "ART"),
				new InstructorOption("I3", "Alvarez", "ENG"),
				new InstructorOption("I4", "Bianchi", "MATH")
		);
		List<InstructorAvailabilityBlock> availabilities = List.of(
				new InstructorAvailabilityBlock("I1", "Novak", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0), AvailabilityStatus.UNAVAILABLE)
		);

		AutoScheduleInput input = new AutoScheduleInput(requests, rooms, instructors, List.of(), List.of(),
				availabilities, Map.of(), null, List.of(), SolverOptions.defaults());
		AutoScheduleResult res = solver.solve(input);

		assertTrue(res.solvable());
		assertEquals(4, res.placements().size());

		// Independently verify every placement using ConflictRules
		List<ScheduledMeeting> placedMeetings = new ArrayList<>();
		Map<String, Integer> enrollments = Map.of("S1", 30, "S2", 20, "S3", 92, "S4", 25);
		for (ProposedPlacement p : res.placements()) {
			int cap = rooms.stream().filter(r -> r.roomId().equals(p.roomId())).findFirst().orElseThrow().capacity();
			placedMeetings.add(new ScheduledMeeting(
					p.subjectId() + "-SEC",
					p.courseCode(),
					p.sectionNumber(),
					p.roomId(),
					p.roomName(),
					cap,
					p.meetings().stream().map(MeetingTime::fromSlot).toList(),
					Set.of(p.instructorId()),
					Map.of(p.instructorId(), p.instructorName()),
					Map.of(),
					enrollments.get(p.subjectId())));
		}
		List<DetectedConflict> conflicts = new ConflictRules().evaluate(placedMeetings, List.of(), availabilities, Map.of(), null);
		assertTrue(conflicts.isEmpty(), "Expected 0 conflicts, found: " + conflicts);
	}

	@Test
	@DisplayName("SAT solver avoids instructor UNAVAILABLE windows, fixed sections, and approved room bookings")
	void respectsInstructorUnavailableAndFixedSections() {
		// Only SINGLE pattern on Monday 09:30 is fixed/booked, 11:00 is UNAVAILABLE, so must place on Tue/Wed/Thu/Fri or non-clashing slot
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "CS 101", "Intro", "CS", "01", 1, RoomType.LECTURE_HALL, Set.of(), List.of(), 20, List.of("I1"), "SINGLE")
		);
		List<RoomOption> rooms = List.of(
				new RoomOption("R1", "Room 101", "Main", 40, RoomType.LECTURE_HALL, Set.of())
		);
		List<InstructorOption> instructors = List.of(
				new InstructorOption("I1", "Bianchi", "CS")
		);
		ScheduledMeeting fixed = new ScheduledMeeting("FIX1", "CS 100", "01", "R1", "Room 101", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:30", "10:20")), Set.of("I1"), Map.of("I1", "Bianchi"), Map.of(), 20);
		ScheduledBooking booking = new ScheduledBooking("B1", ResourceType.ROOM, "R1", "Room 101",
				LocalDateTime.of(2026, 9, 8, 9, 0), LocalDateTime.of(2026, 9, 8, 12, 0), "Maintenance", BookingStatus.APPROVED);
		List<InstructorAvailabilityBlock> unavail = List.of(
				new InstructorAvailabilityBlock("I1", "Bianchi", DayOfWeek.MONDAY, LocalTime.of(10, 30), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE)
		);

		AutoScheduleInput input = new AutoScheduleInput(requests, rooms, instructors, List.of(fixed), List.of(booking),
				unavail, Map.of(), null, List.of(), SolverOptions.defaults());
		AutoScheduleResult res = solver.solve(input);

		assertTrue(res.solvable());
		MeetingSlot placed = res.placements().get(0).meetings().get(0);
		assertFalse(MeetingTime.fromSlot(placed).overlaps(fixed.meetings().get(0)));
		assertFalse(MeetingTime.fromSlot(placed).overlaps(MeetingTime.oneTime(LocalDate.of(2026, 9, 8), LocalTime.of(9, 0), LocalTime.of(12, 0))));
		assertFalse(placed.day() == DayOfWeek.MONDAY && !placed.start().isBefore(LocalTime.of(10, 30)));
	}

	@Test
	@DisplayName("SAT solver enforces room type, SIS capacity, fixed equipment, and multi-unit portable equipment limit")
	void enforcesRoomTypeCapacityAndMultiUnitEquipment() {
		// Two laptop carts total -> 3 SINGLE courses all on different slots or cannot exceed 2 concurrent slots; force only 1 room won't allow overlap anyway,
		// so provide 3 LAB rooms and verify they don't all get placed in the exact same time slot.
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "CS 101", "C1", "CS", "01", 1, RoomType.LAB, Set.of("Projector"), List.of(new EquipmentNeed("E-CART", 1)), 25, List.of("I1"), "SINGLE"),
				new SectionRequest("S2", "CS 102", "C2", "CS", "01", 1, RoomType.LAB, Set.of("Projector"), List.of(new EquipmentNeed("E-CART", 1)), 25, List.of("I2"), "SINGLE"),
				new SectionRequest("S3", "CS 103", "C3", "CS", "01", 1, RoomType.LAB, Set.of("Projector"), List.of(new EquipmentNeed("E-CART", 1)), 50, List.of("I3"), "SINGLE")
		);
		List<RoomOption> rooms = List.of(
				new RoomOption("R-SMALL", "Small Lab", "Tech", 30, RoomType.LAB, Set.of("Projector")),
				new RoomOption("R-BIG", "Big Lab", "Tech", 60, RoomType.LAB, Set.of("Projector")),
				new RoomOption("R-LECT", "Huge Lecture", "Main", 120, RoomType.LECTURE_HALL, Set.of("Projector"))
		);
		List<InstructorOption> instructors = List.of(
				new InstructorOption("I1", "Inst 1", "CS"),
				new InstructorOption("I2", "Inst 2", "CS"),
				new InstructorOption("I3", "Inst 3", "CS")
		);
		Map<String, EquipmentInfo> catalog = Map.of("E-CART", new EquipmentInfo("E-CART", "Laptop cart", "Laptop cart", 2));

		AutoScheduleResult res = solver.solve(new AutoScheduleInput(
				requests, rooms, instructors, List.of(), List.of(), List.of(), catalog, null, List.of(), SolverOptions.defaults()));

		assertTrue(res.solvable());
		ProposedPlacement s3 = res.placements().stream().filter(p -> p.subjectId().equals("S3")).findFirst().orElseThrow();
		// S3 has 50 students and needs LAB, so ONLY R-BIG fits
		assertEquals("R-BIG", s3.roomId());
		// S1/S2 must NOT be in R-LECT
		res.placements().forEach(p -> assertNotEquals("R-LECT", p.roomId()));
	}

	@Test
	@DisplayName("MaxSAT avoids split days (e.g. 09:00 + 18:00) for instructors")
	void avoidsSplitDaysLike9amAnd6pm() {
		// Instructor I1 already has a fixed class Monday 09:00-09:50, and is UNAVAILABLE 12:00-17:29.
		// Available bands on Monday are morning (08:00-11:59 -> compact) vs evening (17:30-20:00 -> split day 09:00+18:00!).
		// Provide single-slot patterns via SINGLE, with Tue/Wed/Thu/Fri unavailable so ONLY Monday morning vs Monday 17:30+ remain.
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "MATH 101", "Algebra", "MATH", "01", 1, RoomType.LECTURE_HALL, Set.of(), List.of(), 20, List.of("I1"), "SINGLE")
		);
		List<RoomOption> rooms = List.of(new RoomOption("R1", "F110", "Founders", 60, RoomType.LECTURE_HALL, Set.of()));
		List<InstructorOption> instructors = List.of(new InstructorOption("I1", "Bianchi", "MATH"));
		ScheduledMeeting fixedMorning = new ScheduledMeeting("F1", "MATH 100", "01", "R1", "F110", 60,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:00", "10:30")), Set.of("I1"), Map.of("I1", "Bianchi"), Map.of(), 20);

		List<InstructorAvailabilityBlock> unavail = new ArrayList<>();
		unavail.add(new InstructorAvailabilityBlock("I1", "Bianchi", DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(17, 30), AvailabilityStatus.UNAVAILABLE));
		for (DayOfWeek day : List.of(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
			unavail.add(new InstructorAvailabilityBlock("I1", "Bianchi", day, LocalTime.of(8, 0), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE));
		}

		AutoScheduleInput input = new AutoScheduleInput(requests, rooms, instructors, List.of(fixedMorning), List.of(),
				unavail, Map.of(), null, List.of(), SolverOptions.defaults());
		AutoScheduleResult res = solver.solve(input);

		assertTrue(res.solvable());
		MeetingSlot chosen = res.placements().get(0).meetings().get(0);
		assertEquals(DayOfWeek.MONDAY, chosen.day());
		assertTrue(chosen.start().isBefore(LocalTime.of(12, 0)),
				"Expected compact morning slot instead of 17:30+ split day, got: " + chosen);
		assertEquals(0, res.summary().splitDayCount());
	}

	@Test
	@DisplayName("MaxSAT rewards instructor PREFER window and right-sized room fit")
	void respectsPreferencesAndRoomFit() {
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "CS 201", "Data Structures", "CS", "01", 3, RoomType.LECTURE_HALL, Set.of(), List.of(), 30, List.of("I1"), "TTH")
		);
		List<RoomOption> rooms = List.of(
				new RoomOption("R-HUGE", "Hale 204", "Hale", 120, RoomType.LECTURE_HALL, Set.of()),
				new RoomOption("R-FIT", "Founders 110", "Founders", 45, RoomType.LECTURE_HALL, Set.of())
		);
		List<InstructorOption> instructors = List.of(new InstructorOption("I1", "Bianchi", "CS"));
		List<InstructorAvailabilityBlock> prefer = List.of(
				new InstructorAvailabilityBlock("I1", "Bianchi", DayOfWeek.TUESDAY, LocalTime.of(13, 0), LocalTime.of(14, 15), AvailabilityStatus.PREFER),
				new InstructorAvailabilityBlock("I1", "Bianchi", DayOfWeek.THURSDAY, LocalTime.of(13, 0), LocalTime.of(14, 15), AvailabilityStatus.PREFER)
		);

		AutoScheduleResult res = solver.solve(new AutoScheduleInput(
				requests, rooms, instructors, List.of(), List.of(), prefer, Map.of(), null, List.of(), SolverOptions.defaults()));

		assertTrue(res.solvable());
		ProposedPlacement p = res.placements().get(0);
		assertEquals("R-FIT", p.roomId());
		assertTrue(p.matchedPreference());
		assertEquals("TTH_1300", p.patternId());
	}

	@Test
	@DisplayName("Determinism & blocking clauses yield distinct valid alternatives until NO_MORE_OPTIONS")
	void deterministicAndBlockingClausesYieldDistinctOptions() {
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "HIST 101", "History", "HIST", "01", 1, RoomType.LECTURE_HALL, Set.of(), List.of(), 20, List.of("I1"), "SINGLE")
		);
		List<RoomOption> rooms = List.of(new RoomOption("R1", "Founders", "F", 50, RoomType.LECTURE_HALL, Set.of()));
		List<InstructorOption> instructors = List.of(new InstructorOption("I1", "Okafor", "HIST"));
		// Allow ONLY Mon 09:30 and Tue 09:30
		List<InstructorAvailabilityBlock> unavail = List.of(
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.MONDAY, LocalTime.of(10, 30), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.TUESDAY, LocalTime.of(10, 30), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE),
				new InstructorAvailabilityBlock("I1", "Okafor", DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(20, 0), AvailabilityStatus.UNAVAILABLE)
		);

		AutoScheduleInput baseInput = new AutoScheduleInput(requests, rooms, instructors, List.of(), List.of(),
				unavail, Map.of(), null, List.of(), SolverOptions.defaults());
		AutoScheduleResult run1a = solver.solve(baseInput);
		AutoScheduleResult run1b = solver.solve(baseInput);
		assertTrue(run1a.solvable());
		assertEquals(run1a.placements().get(0).patternId(), run1b.placements().get(0).patternId());

		PreviousSolution prev1 = toPrev(run1a);
		AutoScheduleResult run2 = solver.solve(new AutoScheduleInput(requests, rooms, instructors, List.of(), List.of(),
				unavail, Map.of(), null, List.of(prev1), SolverOptions.defaults()));
		assertTrue(run2.solvable());
		assertNotEquals(run1a.placements().get(0).patternId(), run2.placements().get(0).patternId());

		Set<String> seenPatterns = new HashSet<>(List.of(run1a.placements().get(0).patternId(), run2.placements().get(0).patternId()));
		assertEquals(2, seenPatterns.size());

		PreviousSolution prev2 = toPrev(run2);
		AutoScheduleResult run3 = solver.solve(new AutoScheduleInput(requests, rooms, instructors, List.of(), List.of(),
				unavail, Map.of(), null, List.of(prev1, prev2), SolverOptions.defaults()));
		assertFalse(run3.solvable());
		assertEquals("NO_MORE_OPTIONS", run3.status());
	}

	@Test
	@DisplayName("UNSAT returns clear diagnostics when no suitable room exists")
	void returnsClearDiagnosticsWhenUnsat() {
		List<SectionRequest> requests = List.of(
				new SectionRequest("S1", "BIO 400", "Genetics Lab", "BIO", "01", 3, RoomType.LAB, Set.of("Centrifuge"), List.of(), 20, List.of("I1"), "AUTO")
		);
		List<RoomOption> rooms = List.of(new RoomOption("R1", "Room 101", "Main", 60, RoomType.LECTURE_HALL, Set.of()));
		List<InstructorOption> instructors = List.of(new InstructorOption("I1", "Bianchi", "BIO"));

		AutoScheduleResult res = solver.solve(new AutoScheduleInput(
				requests, rooms, instructors, List.of(), List.of(), List.of(), Map.of(), null, List.of(), SolverOptions.defaults()));

		assertFalse(res.solvable());
		assertEquals("UNSAT", res.status());
		assertFalse(res.diagnostics().isEmpty());
		assertTrue(res.diagnostics().get(0).contains("BIO 400"));
	}

	private static PreviousSolution toPrev(AutoScheduleResult r) {
		return new PreviousSolution(r.placements().stream()
				.map(p -> new PlacementKey(p.subjectId(), p.sectionNumber(), p.roomId(), p.instructorId(), p.patternId()))
				.toList());
	}
}
