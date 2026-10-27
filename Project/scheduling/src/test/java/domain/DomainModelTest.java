package domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JUnit 5 port of the Phase 2 "Domain Layer Verification Test" (DomainModelTests),
 * which used Java {@code assert} and so passed without checking anything unless run with -ea.
 */
class DomainModelTest {

	// TP3 accounts, as in the Phase 2 test
	private final Scheduler maya = new Scheduler("U01", "Maya Brooks", "m.brooks@univ.edu", List.of("CS", "MATH"));
	private final Instructor bianchi = new Instructor("U02", "L. Bianchi", "l.bianchi@univ.edu", "MATH");
	private final Student jordan = new Student("U03", "Jordan Ellis", "j.ellis@univ.edu", "S10024");

	private final Term fall = new Term("T-2026-FALL", "Fall 2026",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 20), TermStatus.DRAFT);
	private final Room hale204 = new Room("R-204", "Hale Hall 204", "Hale Hall", 120, RoomType.LECTURE_HALL,
			List.of("Projector"), ResourceStatus.AVAILABLE);

	@Test
	@DisplayName("Actors carry their role; schedulers are authorized per department (TR3.4)")
	void actorsAndAuthorization() {
		assertThat(maya.role()).isEqualTo(Role.SCHEDULER);
		assertThat(bianchi.role()).isEqualTo(Role.INSTRUCTOR);
		assertThat(jordan.role()).isEqualTo(Role.STUDENT);
		assertThat(maya.isAuthorizedFor("MATH")).isTrue();
		assertThat(maya.isAuthorizedFor("HIST")).isFalse();
	}

	@Test
	@DisplayName("Placing a section records time, room and instructors")
	void placeSection() {
		Section s = new Section("SEC-101", "MATH 210", fall.termId(), MeetingPattern.WEEKLY, 25);
		assertThat(s.isPlaced()).isFalse();

		s.place(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(11, 15), hale204, List.of(bianchi));

		assertThat(s.isPlaced()).isTrue();
		assertThat(s.instructors()).containsExactly(bianchi);
		assertThat(s.describe()).isEqualTo("MATH 210 (TUESDAY 10:00-11:15, Hale Hall 204)");
	}

	@Test
	@DisplayName("A section needs at least one instructor and a start before its end")
	void placeSectionValidation() {
		Section s = new Section("SEC-101", "MATH 210", fall.termId(), MeetingPattern.WEEKLY, 25);
		assertThatThrownBy(() -> s.place(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(11, 0), hale204, List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> s.place(DayOfWeek.TUESDAY, LocalTime.of(11, 0), LocalTime.of(11, 0), hale204, List.of(bianchi)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Overlapping sections overlap; back-to-back sections do not")
	void sectionOverlap() {
		Section a = placed("A", LocalTime.of(10, 0), LocalTime.of(11, 0));
		Section b = placed("B", LocalTime.of(10, 30), LocalTime.of(11, 30));
		Section c = placed("C", LocalTime.of(11, 0), LocalTime.of(12, 0));

		assertThat(a.overlaps(b)).isTrue();
		assertThat(a.overlaps(c)).isFalse();
	}

	@Test
	@DisplayName("A booking starts Pending, can be approved once, and can be cancelled")
	void bookingFlow() {
		Booking booking = bianchi.requestBooking("B-001", ResourceType.ROOM, hale204, null,
				LocalDateTime.of(2026, 10, 20, 14, 0), LocalDateTime.of(2026, 10, 20, 16, 0), "Guest Lecture");

		assertThat(booking.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(booking.approve()).isTrue();
		assertThat(booking.status()).isEqualTo(BookingStatus.APPROVED);
		assertThat(booking.approve()).as("second approval is refused").isFalse();

		booking.cancel("Speaker unavailable");
		assertThat(booking.status()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(booking.cancelNote()).isEqualTo("Speaker unavailable");
	}

	@Test
	@DisplayName("A room booking without a room is rejected")
	void bookingNeedsResource() {
		assertThatThrownBy(() -> bianchi.requestBooking("B-002", ResourceType.ROOM, null, null,
				LocalDateTime.of(2026, 10, 20, 14, 0), LocalDateTime.of(2026, 10, 20, 16, 0), "x"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("NFR3.3: dismissing a conflict records who, when, which conflict and why")
	void conflictDismissalAudit() {
		Conflict conflict = new Conflict("C-101", ConflictType.ROOM_DOUBLE_BOOKING, Severity.HIGH,
				"Room collision detected", List.of("SEC-101"), null);
		LocalDateTime at = LocalDateTime.of(2026, 10, 14, 9, 0);

		AuditLogEntry audit = conflict.dismiss(maya, "Overridden for special campus event", at);

		assertThat(conflict.resolutionState()).isEqualTo(ResolutionState.DISMISSED);
		assertThat(audit.dismissedByUserId()).isEqualTo("U01");
		assertThat(audit.conflictId()).isEqualTo("C-101");
		assertThat(audit.timestamp()).isEqualTo(at);
		assertThat(audit.reason()).isEqualTo("Overridden for special campus event");
	}

	@Test
	@DisplayName("NFR3.3: a conflict cannot be dismissed without a reason")
	void dismissNeedsReason() {
		Conflict conflict = new Conflict("C-102", ConflictType.CAPACITY_EXCEEDED, Severity.MEDIUM, "x", List.of(), null);
		assertThatThrownBy(() -> conflict.dismiss(maya, " ", LocalDateTime.of(2026, 10, 14, 9, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(conflict.resolutionState()).isEqualTo(ResolutionState.OPEN);
	}

	@Test
	@DisplayName("TC-FR1.1: a copied term starts as Draft; publishing locks it")
	void termCopyAndPublish() {
		fall.publish();
		assertThat(fall.isLocked()).isTrue();

		Term copy = fall.copy("T-2027-SPRING", "Spring 2027");
		assertThat(copy.status()).isEqualTo(TermStatus.DRAFT);
		assertThat(copy.isLocked()).isFalse();
	}

	@Test
	@DisplayName("TC-FR3.1: a room with capacity -5 is rejected")
	void roomCapacityValidation() {
		assertThatThrownBy(() -> new Room("R-X", "Bad Room", "Hale Hall", -5, RoomType.LAB, null, ResourceStatus.AVAILABLE))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Rooms under maintenance are not bookable (TC-FR2.4)")
	void maintenanceRoomNotBookable() {
		hale204.setStatus(ResourceStatus.MAINTENANCE);
		assertThat(hale204.isBookable()).isFalse();
	}

	private Section placed(String id, LocalTime start, LocalTime end) {
		Section s = new Section(id, "MATH " + id, fall.termId(), MeetingPattern.WEEKLY, 20);
		s.place(DayOfWeek.MONDAY, start, end, hale204, List.of(bianchi));
		return s;
	}

}
