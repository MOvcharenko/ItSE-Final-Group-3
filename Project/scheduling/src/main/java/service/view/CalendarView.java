package service.view;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import domain.CalendarScope;
import domain.MeetingPattern;
import domain.TermStatus;

/**
 * One calendar view. enrollmentAvailable is false when the SIS could not say which sections a student is enrolled in:
 * the list is then empty because the data is missing, not because the student has no classes.
 */
public record CalendarView(CalendarScope scope, LocalDate from, LocalDate to, boolean enrollmentAvailable,
		List<Event> events) {

	/** One meeting on one date. Weekly meetings appear once per date. */
	public record Event(String sectionId, String courseCode, String sectionNumber, MeetingPattern pattern, LocalDate date,
			LocalTime start, LocalTime end, String roomId, String roomName, String building, List<String> instructors,
			String termId, String termName, TermStatus termStatus) {
	}
}
