package core.conflict;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import core.MeetingTime;
import domain.MeetingPattern;

/**
 * One meeting of one section that takes part in a conflict, so a timetable can flag that meeting rather than every
 * meeting of the section. A weekly meeting has a day and no date; a one-time meeting or exam has a date and no day.
 */
public record ConflictMeeting(
		String sectionId,
		MeetingPattern pattern,
		DayOfWeek dayOfWeek,
		LocalDate date,
		LocalTime start,
		LocalTime end
) {
	public ConflictMeeting {
		Objects.requireNonNull(sectionId, "sectionId must not be null");
		Objects.requireNonNull(pattern, "pattern must not be null");
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(end, "end must not be null");
	}

	public static ConflictMeeting of(String sectionId, MeetingTime meeting) {
		boolean weekly = meeting.pattern() == MeetingPattern.WEEKLY;
		return new ConflictMeeting(sectionId, meeting.pattern(), weekly ? meeting.dayOfWeek() : null,
				weekly ? null : meeting.date(), meeting.start(), meeting.end());
	}
}
