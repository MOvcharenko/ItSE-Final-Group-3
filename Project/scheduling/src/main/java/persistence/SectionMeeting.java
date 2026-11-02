package persistence;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import domain.MeetingPattern;
import domain.MeetingSlot;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** One row of table section_meeting. Lives inside a SectionEntity; "MWF" is three of these. */
@Embeddable
public class SectionMeeting {

	@Column(name = "id", nullable = false, length = 36)
	private String id;

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

	protected SectionMeeting() {
	}

	SectionMeeting(MeetingSlot slot) {
		this.id = UUID.randomUUID().toString();
		this.pattern = slot.pattern();
		this.dayOfWeek = slot.day();
		this.meetingDate = slot.date();
		this.startTime = slot.start();
		this.endTime = slot.end();
	}

	MeetingSlot toSlot() {
		return new MeetingSlot(pattern, dayOfWeek, meetingDate, startTime, endTime);
	}
}
