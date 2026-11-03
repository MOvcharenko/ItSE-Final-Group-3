package core;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import domain.MeetingPattern;
import domain.MeetingSlot;

/** Turns meetings into the real dates they fall on, for the calendar views (FR6.1). */
public final class CalendarDates {

	private CalendarDates() {
	}

	public static LocalDate weekStart(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	public static LocalDate weekEnd(LocalDate date) {
		return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
	}

	/**
	 * The dates a meeting falls on within both its term and the window [from, to], first to last. A weekly meeting
	 * repeats through the term; a one-time meeting or exam has only its own date.
	 */
	public static List<LocalDate> datesOf(MeetingSlot meeting, LocalDate termStart, LocalDate termEnd, LocalDate from,
			LocalDate to) {
		LocalDate first = from.isAfter(termStart) ? from : termStart;
		LocalDate last = to.isBefore(termEnd) ? to : termEnd;
		if (meeting.pattern() != MeetingPattern.WEEKLY) {
			boolean inside = !meeting.date().isBefore(first) && !meeting.date().isAfter(last);
			return inside ? List.of(meeting.date()) : List.of();
		}
		LocalDate firstMeeting = first.with(TemporalAdjusters.nextOrSame(meeting.day()));
		if (firstMeeting.isAfter(last)) {
			return List.of();
		}
		return firstMeeting.datesUntil(last.plusDays(1), Period.ofWeeks(1)).toList();
	}

}
