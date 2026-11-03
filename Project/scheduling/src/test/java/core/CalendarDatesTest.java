package core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import domain.MeetingPattern;
import domain.MeetingSlot;

class CalendarDatesTest {

	/** A term from Wed 2 Sep to Wed 30 Sep 2026. */
	private static final LocalDate TERM_START = LocalDate.of(2026, 9, 2);
	private static final LocalDate TERM_END = LocalDate.of(2026, 9, 30);

	private static MeetingSlot weekly(DayOfWeek day) {
		return new MeetingSlot(MeetingPattern.WEEKLY, day, null, LocalTime.of(10, 0), LocalTime.of(11, 0));
	}

	private static MeetingSlot exam(LocalDate date) {
		return new MeetingSlot(MeetingPattern.EXAM, null, date, LocalTime.of(9, 0), LocalTime.of(11, 0));
	}

	private static LocalDate sep(int day) {
		return LocalDate.of(2026, 9, day);
	}

	@Test
	@DisplayName("TC-FR6.1: a week runs Monday to Sunday, whichever day it is asked about")
	void week() {
		assertThat(CalendarDates.weekStart(sep(16))).isEqualTo(sep(14));
		assertThat(CalendarDates.weekEnd(sep(16))).isEqualTo(sep(20));
		assertThat(CalendarDates.weekStart(sep(14))).isEqualTo(sep(14));
		assertThat(CalendarDates.weekEnd(sep(20))).isEqualTo(sep(20));
	}

	@Test
	@DisplayName("TC-FR6.1: a weekly meeting falls on its day each week, and only inside the term")
	void wholeTerm() {
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, TERM_START, TERM_END))
			.containsExactly(sep(7), sep(14), sep(21), sep(28));
	}

	@Test
	@DisplayName("TC-FR6.1: a meeting on the first or last day of the term is included")
	void termBoundaries() {
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.WEDNESDAY), TERM_START, TERM_END, TERM_START, TERM_END))
			.containsExactly(sep(2), sep(9), sep(16), sep(23), sep(30));
	}

	@Test
	@DisplayName("TC-FR6.1: a window limits the dates, and its first and last days count")
	void window() {
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, sep(14), sep(21)))
			.containsExactly(sep(14), sep(21));
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, sep(14), sep(14)))
			.containsExactly(sep(14));
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, sep(15), sep(15))).isEmpty();
	}

	@Test
	@DisplayName("TC-FR6.1: a window that misses the term finds nothing")
	void outsideTerm() {
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, sep(1), sep(1))).isEmpty();
		assertThat(CalendarDates.datesOf(weekly(DayOfWeek.MONDAY), TERM_START, TERM_END, LocalDate.of(2026, 10, 5),
				LocalDate.of(2026, 10, 11))).isEmpty();
	}

	@Test
	@DisplayName("TC-FR6.1: an exam or one-time meeting has only its own date, when the window includes it")
	void dated() {
		assertThat(CalendarDates.datesOf(exam(sep(15)), TERM_START, TERM_END, sep(14), sep(20))).containsExactly(sep(15));
		assertThat(CalendarDates.datesOf(exam(sep(15)), TERM_START, TERM_END, sep(15), sep(15))).containsExactly(sep(15));
		assertThat(CalendarDates.datesOf(exam(sep(15)), TERM_START, TERM_END, sep(16), sep(20))).isEmpty();
	}

}
