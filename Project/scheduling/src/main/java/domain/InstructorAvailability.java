package domain;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * InstructorAvailability — one block of an instructor's availability submission (TR2.7).
 */
public class InstructorAvailability {

	private final String availabilityId;
	private final String instructorId;
	private final String termId;
	private final DayOfWeek dayOfWeek;
	private final LocalTime startTime;
	private final LocalTime endTime;
	private final AvailabilityStatus status;

	public InstructorAvailability(String availabilityId, String instructorId, String termId,
			DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, AvailabilityStatus status) {
		if (!startTime.isBefore(endTime)) {
			throw new IllegalArgumentException("Start time must be before end time.");
		}
		this.availabilityId = availabilityId;
		this.instructorId = instructorId;
		this.termId = termId;
		this.dayOfWeek = dayOfWeek;
		this.startTime = startTime;
		this.endTime = endTime;
		this.status = status;
	}

	public boolean isUnavailable() { return status == AvailabilityStatus.UNAVAILABLE; }

	public String availabilityId() { return availabilityId; }
	public String instructorId() { return instructorId; }
	public String termId() { return termId; }
	public DayOfWeek day() { return dayOfWeek; }
	public LocalTime start() { return startTime; }
	public LocalTime end() { return endTime; }
	public AvailabilityStatus status() { return status; }

}
