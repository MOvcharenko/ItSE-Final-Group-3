package persistence;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import core.conflict.ConflictMeeting;
import domain.MeetingPattern;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** One row of table conflict_meeting: a meeting of one section that takes part in a conflict. */
@Embeddable
public class ConflictMeetingRow {

	@Column(name = "section_id", nullable = false, length = 36)
	private String sectionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "pattern", nullable = false, length = 10)
	private MeetingPattern pattern;

	@Enumerated(EnumType.STRING)
	@Column(name = "day_of_week", length = 10)
	private DayOfWeek dayOfWeek;

	@Column(name = "meeting_date")
	private LocalDate meetingDate;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	protected ConflictMeetingRow() {
	}

	public ConflictMeetingRow(ConflictMeeting meeting) {
		this.sectionId = meeting.sectionId();
		this.pattern = meeting.pattern();
		this.dayOfWeek = meeting.dayOfWeek();
		this.meetingDate = meeting.date();
		this.startTime = meeting.start();
		this.endTime = meeting.end();
	}

	public ConflictMeeting toMeeting() {
		return new ConflictMeeting(sectionId, pattern, dayOfWeek, meetingDate, startTime, endTime);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ConflictMeetingRow row && toMeeting().equals(row.toMeeting());
	}

	@Override
	public int hashCode() {
		return Objects.hash(sectionId, pattern, dayOfWeek, meetingDate, startTime, endTime);
	}
}
