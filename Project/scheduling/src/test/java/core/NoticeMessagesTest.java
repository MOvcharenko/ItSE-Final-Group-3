package core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import domain.MeetingPattern;
import domain.MeetingSlot;

class NoticeMessagesTest {

	private static final String SECTION = NoticeMessages.sectionLabel("MATH 210", "01");
	private static final LocalTime TEN = LocalTime.of(10, 0);
	private static final LocalTime ELEVEN = LocalTime.of(11, 0);

	private static MeetingSlot weekly(DayOfWeek day) {
		return new MeetingSlot(MeetingPattern.WEEKLY, day, null, TEN, ELEVEN);
	}

	@Test
	@DisplayName("UC-18: a change names the section, its new times in a stable order, the room and the instructors")
	void sectionChanged() {
		MeetingSlot exam = new MeetingSlot(MeetingPattern.EXAM, null, LocalDate.of(2026, 12, 15), TEN, ELEVEN);
		MeetingSlot once = new MeetingSlot(MeetingPattern.ONE_TIME, null, LocalDate.of(2026, 10, 1), TEN, ELEVEN);

		String message = NoticeMessages.sectionChanged(SECTION, "Founders 110",
				List.of(exam, weekly(DayOfWeek.WEDNESDAY), once, weekly(DayOfWeek.MONDAY)), List.of("L. Bianchi", "A. Novak"));

		assertThat(message).isEqualTo("MATH 210 section 01 has changed. It is in Founders 110 and meets "
				+ "Mon 10:00–11:00, Wed 10:00–11:00, once on Oct 1 10:00–11:00, "
				+ "for the exam on Dec 15 10:00–11:00. Instructors: A. Novak, L. Bianchi.");
	}

	@Test
	@DisplayName("UC-18: a message that fits is left alone, up to and including the column's 1000 characters")
	void fitLeavesShortMessages() {
		assertThat(NoticeMessages.fit("short")).isEqualTo("short");
		String exact = "x".repeat(1000);
		assertThat(NoticeMessages.fit(exact)).isEqualTo(exact);
	}

	@Test
	@DisplayName("UC-18: a longer message is cut to 1000 characters and points to the calendar")
	void fitCutsLongMessages() {
		String fitted = NoticeMessages.fit("x".repeat(1001));

		assertThat(fitted).hasSize(1000).startsWith("xxx").endsWith(" ... See your calendar for the full details.");
	}

	@Test
	@DisplayName("UC-18: cutting never splits a character")
	void fitKeepsCharactersWhole() {
		int keptChars = 1000 - " ... See your calendar for the full details.".length();
		String emoji = "😀";

		String fitted = NoticeMessages.fit("x".repeat(keptChars - 1) + emoji + "y".repeat(100));

		assertThat(fitted).hasSizeLessThanOrEqualTo(1000).doesNotContain("\uD83D").startsWith("xxx");
	}

	@Test
	@DisplayName("UC-18: a section with no room says so")
	void noRoom() {
		assertThat(NoticeMessages.sectionChanged(SECTION, null, List.of(weekly(DayOfWeek.MONDAY)), List.of("L. Bianchi")))
			.isEqualTo("MATH 210 section 01 has changed. It has no room assigned yet and meets Mon 10:00–11:00."
					+ " Instructors: L. Bianchi.");
	}

	@Test
	@DisplayName("UC-18: new instructors are listed by name in a stable order")
	void instructorsChanged() {
		assertThat(NoticeMessages.instructorsChanged(SECTION, List.of("L. Bianchi", "A. Novak")))
			.isEqualTo("The instructors for MATH 210 section 01 have changed. Now teaching: A. Novak, L. Bianchi.");
	}

	@Test
	@DisplayName("UC-08: a booking on one day shows one date")
	void bookingSameDay() {
		assertThat(NoticeMessages.bookingApproved("Founders 110", LocalDateTime.of(2026, 11, 10, 17, 0),
				LocalDateTime.of(2026, 11, 10, 18, 30)))
			.isEqualTo("Your request for Founders 110 on Nov 10, 17:00–18:30 was approved.");
	}

	@Test
	@DisplayName("UC-08: a booking across days shows both dates")
	void bookingAcrossDays() {
		assertThat(NoticeMessages.bookingDeclined("Projector 1", LocalDateTime.of(2026, 11, 10, 17, 0),
				LocalDateTime.of(2026, 11, 11, 9, 0), null))
			.isEqualTo("Your request for Projector 1 on Nov 10, 17:00 to Nov 11, 09:00 was declined.");
	}

	@Test
	@DisplayName("UC-08: a declined request includes the Scheduler's note only when there is one")
	void bookingDeclined() {
		LocalDateTime start = LocalDateTime.of(2026, 11, 10, 17, 0);
		LocalDateTime end = LocalDateTime.of(2026, 11, 10, 18, 0);

		assertThat(NoticeMessages.bookingDeclined("Founders 110", start, end, "Room closed"))
			.endsWith("was declined. Note: Room closed");
		assertThat(NoticeMessages.bookingDeclined("Founders 110", start, end, " ")).endsWith("was declined.");
		assertThat(NoticeMessages.bookingDeclined("Founders 110", start, end, null)).endsWith("was declined.");
	}

	@Test
	@DisplayName("UC-08: a cancellation includes the Scheduler's note only when there is one")
	void bookingCancelled() {
		LocalDateTime start = LocalDateTime.of(2026, 11, 10, 17, 0);
		LocalDateTime end = LocalDateTime.of(2026, 11, 10, 18, 0);

		assertThat(NoticeMessages.bookingCancelled("Founders 110", start, end, "Room closed"))
			.endsWith("was cancelled. Note: Room closed");
		assertThat(NoticeMessages.bookingCancelled("Founders 110", start, end, " ")).endsWith("was cancelled.");
		assertThat(NoticeMessages.bookingCancelled("Founders 110", start, end, null)).endsWith("was cancelled.");
	}

}
