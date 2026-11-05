package core.conflict;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import core.MeetingTime;
import domain.AvailabilityStatus;
import domain.BookingStatus;
import domain.ConflictType;
import domain.ResourceType;
import domain.Severity;

class ConflictRulesTest {

	private final ConflictRules engine = new ConflictRules();

	@Test
	@DisplayName("TC-FR4.3a: Room double-booking is flagged naming both sections, touching back-to-back pair is not flagged")
	void roomDoubleBookingAndBackToBack() {
		// Overlapping pair: CS 340 and ART 220 in Innovation Lab B, Monday 1:00–2:15 pm
		ScheduledMeeting cs340 = new ScheduledMeeting(
				"sec-cs340", "CS 340", "01", "room-innoB", "Innovation Lab B", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("novak"), Map.of("novak", "Dr. Novak"), Map.of(), 25
		);
		ScheduledMeeting art220 = new ScheduledMeeting(
				"sec-art220", "ART 220", "01", "room-innoB", "Innovation Lab B", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("alvarez"), Map.of("alvarez", "Prof. Alvarez"), Map.of(), 20
		);

		List<DetectedConflict> conflicts = engine.evaluate(List.of(cs340, art220), List.of(), List.of(), Map.of(), null);

		assertThat(conflicts).hasSize(1);
		DetectedConflict c = conflicts.getFirst();
		assertThat(c.type()).isEqualTo(ConflictType.ROOM_DOUBLE_BOOKING);
		assertThat(c.severity()).isEqualTo(Severity.HIGH);
		assertThat(c.explanation()).contains("Innovation Lab B", "CS 340", "ART 220");
		assertThat(c.sectionIds()).containsExactlyInAnyOrder("sec-cs340", "sec-art220");

		// Back-to-back pair: 10:00–11:15, then 11:15–12:00 in Founders 110
		ScheduledMeeting b2b1 = new ScheduledMeeting(
				"sec-b1", "MATH 101", "01", "room-f110", "Founders 110", 50,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "10:00", "11:15")),
				Set.of("smith"), Map.of(), Map.of(), 30
		);
		ScheduledMeeting b2b2 = new ScheduledMeeting(
				"sec-b2", "MATH 102", "01", "room-f110", "Founders 110", 50,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "11:15", "12:00")),
				Set.of("jones"), Map.of(), Map.of(), 30
		);

		List<DetectedConflict> b2bConflicts = engine.evaluate(List.of(b2b1, b2b2), List.of(), List.of(), Map.of(), null);
		assertThat(b2bConflicts).isEmpty();
	}

	@Test
	@DisplayName("TC-FR4.3a: Room double-booking between section and approved room booking is flagged")
	void roomBookingDoubleBooking() {
		ScheduledMeeting cs340 = new ScheduledMeeting(
				"sec-cs340", "CS 340", "01", "room-innoB", "Innovation Lab B", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("novak"), Map.of(), Map.of(), 25
		);
		// 2026-10-12 is a Monday
		ScheduledBooking approvedBooking = new ScheduledBooking(
				"book-1", ResourceType.ROOM, "room-innoB", "Innovation Lab B",
				LocalDateTime.of(2026, 10, 12, 13, 30),
				LocalDateTime.of(2026, 10, 12, 15, 0),
				"Guest lecture", BookingStatus.APPROVED
		);
		ScheduledBooking pendingBooking = new ScheduledBooking(
				"book-2", ResourceType.ROOM, "room-innoB", "Innovation Lab B",
				LocalDateTime.of(2026, 10, 12, 13, 0),
				LocalDateTime.of(2026, 10, 12, 14, 0),
				"Pending workshop", BookingStatus.PENDING
		);

		List<DetectedConflict> conflicts = engine.evaluate(List.of(cs340), List.of(approvedBooking, pendingBooking), List.of(), Map.of(), null);

		assertThat(conflicts).hasSize(1);
		DetectedConflict c = conflicts.getFirst();
		assertThat(c.type()).isEqualTo(ConflictType.ROOM_DOUBLE_BOOKING);
		assertThat(c.severity()).isEqualTo(Severity.HIGH);
		assertThat(c.explanation()).contains("Innovation Lab B", "CS 340", "Guest lecture");
		assertThat(c.bookingId()).isEqualTo("book-1");
	}

	@Test
	@DisplayName("TC-FR4.3b: Instructor overlap flags naming both meetings and the 45-minute overlap")
	void instructorOverlap() {
		// Dr. Novak scheduled for CS 340 (Mon 13:00–14:15) and Thesis Seminar (Mon 13:30–14:30)
		ScheduledMeeting cs340 = new ScheduledMeeting(
				"sec-cs340", "CS 340", "01", "room-1", "Room 1", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("novak"), Map.of("novak", "Dr. Novak"), Map.of(), 25
		);
		ScheduledMeeting seminar = new ScheduledMeeting(
				"sec-sem", "Thesis Seminar", "01", "room-2", "Room 2", 20,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:30", "14:30")),
				Set.of("novak"), Map.of("novak", "Dr. Novak"), Map.of(), 10
		);

		List<DetectedConflict> conflicts = engine.evaluate(List.of(cs340, seminar), List.of(), List.of(), Map.of(), null);

		assertThat(conflicts).hasSize(1);
		DetectedConflict c = conflicts.getFirst();
		assertThat(c.type()).isEqualTo(ConflictType.INSTRUCTOR_OVERLAP);
		assertThat(c.severity()).isEqualTo(Severity.HIGH);
		assertThat(c.explanation()).contains("Dr. Novak", "CS 340", "Thesis Seminar", "45 minutes");
		assertThat(c.sectionIds()).containsExactlyInAnyOrder("sec-cs340", "sec-sem");
	}

	@Test
	@DisplayName("TC-FR4.3c: Instructor unavailability flagged for overlap, moving to start at 2:00 pm clears it")
	void instructorUnavailability() {
		// Dr. Novak Unavailable Monday 12:00–14:00
		InstructorAvailabilityBlock unavailableBlock = new InstructorAvailabilityBlock(
				"novak", "Dr. Novak", DayOfWeek.MONDAY,
				java.time.LocalTime.of(12, 0), java.time.LocalTime.of(14, 0),
				AvailabilityStatus.UNAVAILABLE
		);

		// CS 340 at 1:00–2:15 pm (overlaps with 12:00–14:00)
		ScheduledMeeting cs340Overlapping = new ScheduledMeeting(
				"sec-cs340", "CS 340", "01", "room-1", "Room 1", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("novak"), Map.of("novak", "Dr. Novak"), Map.of(), 25
		);

		List<DetectedConflict> c1 = engine.evaluate(List.of(cs340Overlapping), List.of(), List.of(unavailableBlock), Map.of(), null);
		assertThat(c1).hasSize(1);
		assertThat(c1.getFirst().type()).isEqualTo(ConflictType.INSTRUCTOR_UNAVAILABLE);
		assertThat(c1.getFirst().severity()).isEqualTo(Severity.HIGH);
		assertThat(c1.getFirst().explanation()).contains("Dr. Novak", "CS 340");

		// Move CS 340 to start at exactly 2:00 pm (14:00–15:15)
		ScheduledMeeting cs340AtTwo = new ScheduledMeeting(
				"sec-cs340", "CS 340", "01", "room-1", "Room 1", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "14:00", "15:15")),
				Set.of("novak"), Map.of("novak", "Dr. Novak"), Map.of(), 25
		);

		List<DetectedConflict> c2 = engine.evaluate(List.of(cs340AtTwo), List.of(), List.of(unavailableBlock), Map.of(), null);
		assertThat(c2).isEmpty();
	}

	@Test
	@DisplayName("TC-FR4.3d: Equipment double-booking per-unit check (2 reserved + 1 section = clash, 1 reserved + 1 section = free unit)")
	void equipmentDoubleBookingPerUnit() {
		EquipmentInfo laptopCart = new EquipmentInfo("eq-laptop", "Laptop cart", "Laptop cart", 2);
		Map<String, EquipmentInfo> catalog = Map.of("eq-laptop", laptopCart);

		// Section needing 1 Laptop cart Monday 13:00–14:15
		ScheduledMeeting section = new ScheduledMeeting(
				"sec-cs101", "CS 101", "01", "room-1", "Room 1", 30,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15")),
				Set.of("smith"), Map.of(), Map.of("eq-laptop", 1), 20
		);

		// 2026-10-12 is Monday
		ScheduledBooking b1 = new ScheduledBooking(
				"b-1", ResourceType.EQUIPMENT, "eq-laptop", "Laptop cart",
				LocalDateTime.of(2026, 10, 12, 13, 0),
				LocalDateTime.of(2026, 10, 12, 15, 0),
				"Workshop 1", BookingStatus.APPROVED
		);
		ScheduledBooking b2 = new ScheduledBooking(
				"b-2", ResourceType.EQUIPMENT, "eq-laptop", "Laptop cart",
				LocalDateTime.of(2026, 10, 12, 13, 0),
				LocalDateTime.of(2026, 10, 12, 15, 0),
				"Workshop 2", BookingStatus.APPROVED
		);

		// Case 1: Both carts reserved + 1 section needing 1 cart => 3 units requested > 2 capacity => flagged
		List<DetectedConflict> conflictsBoth = engine.evaluate(List.of(section), List.of(b1, b2), List.of(), catalog, null);
		assertThat(conflictsBoth).hasSize(1);
		DetectedConflict c = conflictsBoth.getFirst();
		assertThat(c.type()).isEqualTo(ConflictType.EQUIPMENT_DOUBLE_BOOKING);
		assertThat(c.severity()).isEqualTo(Severity.MEDIUM);
		assertThat(c.explanation()).contains("Laptop cart", "3 units requested", "only 2 available");

		// Case 2: Only 1 cart reserved + 1 section needing 1 cart => 2 units requested <= 2 capacity => not flagged
		List<DetectedConflict> conflictsOne = engine.evaluate(List.of(section), List.of(b1), List.of(), catalog, null);
		assertThat(conflictsOne).isEmpty();
	}

	@Test
	@DisplayName("TC-FR4.3e: Capacity exceeded flags 92 and 81 against capacity 80, 80 is not flagged")
	void capacityExceeded() {
		// Carter Center 301 has capacity 80
		ScheduledMeeting eng101Over92 = new ScheduledMeeting(
				"sec-eng", "ENG 101", "01", "room-cc301", "Carter Center 301", 80,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:00", "10:15")),
				Set.of("prof"), Map.of(), Map.of(), 92
		);
		List<DetectedConflict> c92 = engine.evaluate(List.of(eng101Over92), List.of(), List.of(), Map.of(), null);
		assertThat(c92).hasSize(1);
		assertThat(c92.getFirst().type()).isEqualTo(ConflictType.CAPACITY_EXCEEDED);
		assertThat(c92.getFirst().severity()).isEqualTo(Severity.MEDIUM);
		assertThat(c92.getFirst().explanation()).contains("ENG 101", "Carter Center 301", "92", "80");

		ScheduledMeeting eng101Over81 = new ScheduledMeeting(
				"sec-eng", "ENG 101", "01", "room-cc301", "Carter Center 301", 80,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:00", "10:15")),
				Set.of("prof"), Map.of(), Map.of(), 81
		);
		List<DetectedConflict> c81 = engine.evaluate(List.of(eng101Over81), List.of(), List.of(), Map.of(), null);
		assertThat(c81).hasSize(1);

		ScheduledMeeting eng101Equal80 = new ScheduledMeeting(
				"sec-eng", "ENG 101", "01", "room-cc301", "Carter Center 301", 80,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:00", "10:15")),
				Set.of("prof"), Map.of(), Map.of(), 80
		);
		List<DetectedConflict> c80 = engine.evaluate(List.of(eng101Equal80), List.of(), List.of(), Map.of(), null);
		assertThat(c80).isEmpty();
	}

	@Test
	@DisplayName("TC-FR4.3f: Student-group clash flags 14 shared students, single student under threshold is not flagged")
	void studentGroupClash() {
		// Overlapping meetings on Tuesday
		ScheduledMeeting math210 = new ScheduledMeeting(
				"sec-math", "MATH 210", "01", "room-1", "Room 1", 50,
				List.of(MeetingTime.weekly(DayOfWeek.TUESDAY, "10:00", "11:15")),
				Set.of("m-prof"), Map.of(), Map.of(), 40
		);
		ScheduledMeeting phys110 = new ScheduledMeeting(
				"sec-phys", "PHYS 110 Lab", "01", "room-2", "Room 2", 30,
				List.of(MeetingTime.weekly(DayOfWeek.TUESDAY, "10:30", "12:00")),
				Set.of("p-prof"), Map.of(), Map.of(), 25
		);

		// 14 shared students (>= default threshold 5) => flagged
		List<DetectedConflict> c14 = engine.evaluate(
				List.of(math210, phys110), List.of(), List.of(), Map.of(),
				(s1, s2) -> 14
		);
		assertThat(c14).hasSize(1);
		DetectedConflict c = c14.getFirst();
		assertThat(c.type()).isEqualTo(ConflictType.STUDENT_GROUP_CLASH);
		assertThat(c.severity()).isEqualTo(Severity.LOW);
		assertThat(c.explanation()).contains("MATH 210", "PHYS 110 Lab", "14 shared students");

		// 1 shared student (< default threshold 5) => not flagged
		List<DetectedConflict> c1 = engine.evaluate(
				List.of(math210, phys110), List.of(), List.of(), Map.of(),
				(s1, s2) -> 1
		);
		assertThat(c1).isEmpty();
	}

	@Test
	@DisplayName("TC-FR4.4: Every conflict type maps to its specified severity")
	void severityMapping() {
		assertThat(DetectedConflict.roomDoubleBooking("e", "f", "r", null, List.of("s1")).severity())
				.isEqualTo(Severity.HIGH);
		assertThat(DetectedConflict.instructorOverlap("e", "f", "i", List.of("s1")).severity())
				.isEqualTo(Severity.HIGH);
		assertThat(DetectedConflict.instructorUnavailable("e", "f", "i", "s1").severity())
				.isEqualTo(Severity.HIGH);
		assertThat(DetectedConflict.equipmentDoubleBooking("e", "f", "eq", null, List.of("s1")).severity())
				.isEqualTo(Severity.MEDIUM);
		assertThat(DetectedConflict.capacityExceeded("e", "f", "r", "s1").severity())
				.isEqualTo(Severity.MEDIUM);
		assertThat(DetectedConflict.studentGroupClash("e", "f", List.of("s1", "s2")).severity())
				.isEqualTo(Severity.LOW);
	}

	@Test
	@DisplayName("TC-FR4.6: Prefer availability blocks are advisory only and never generate conflicts")
	void preferIsAdvisory() {
		// Prof. Bianchi declared PREFER for Monday 10:00–12:00
		InstructorAvailabilityBlock preferBlock = new InstructorAvailabilityBlock(
				"bianchi", "Prof. Bianchi", DayOfWeek.MONDAY,
				java.time.LocalTime.of(10, 0), java.time.LocalTime.of(12, 0),
				AvailabilityStatus.PREFER
		);

		// Section placed inside the PREFER window
		ScheduledMeeting insidePrefer = new ScheduledMeeting(
				"sec-b1", "MATH 201", "01", "room-1", "Room 1", 40,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "10:30", "11:45")),
				Set.of("bianchi"), Map.of("bianchi", "Prof. Bianchi"), Map.of(), 20
		);

		List<DetectedConflict> conflicts = engine.evaluate(List.of(insidePrefer), List.of(), List.of(preferBlock), Map.of(), null);
		assertThat(conflicts).isEmpty();
	}

	@Test
	@DisplayName("Graceful degradation: null shared students lookup or null enrollment count skips rules without failing")
	void gracefulDegradation() {
		ScheduledMeeting m = new ScheduledMeeting(
				"sec-1", "CS 101", "01", "room-1", "Room 1", 30,
				List.of(MeetingTime.weekly(DayOfWeek.MONDAY, "09:00", "10:15")),
				Set.of("prof"), Map.of(), Map.of(), null // enrollment is null (SIS unreachable)
		);

		// Passing null lookup skips student-group clash and capacity exceeded
		List<DetectedConflict> conflicts = engine.evaluate(List.of(m), List.of(), List.of(), Map.of(), null);
		assertThat(conflicts).isEmpty();
	}
}
