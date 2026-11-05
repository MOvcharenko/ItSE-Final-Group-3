package core.conflict;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

import domain.AvailabilityStatus;

/**
 * Immutable snapshot of an instructor availability block.
 * Framework-free: plain Java record.
 */
public record InstructorAvailabilityBlock(
		String instructorId,
		String instructorName,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime,
		AvailabilityStatus status
) {
	public InstructorAvailabilityBlock {
		Objects.requireNonNull(instructorId, "instructorId must not be null");
		Objects.requireNonNull(dayOfWeek, "dayOfWeek must not be null");
		Objects.requireNonNull(startTime, "startTime must not be null");
		Objects.requireNonNull(endTime, "endTime must not be null");
		Objects.requireNonNull(status, "status must not be null");
		instructorName = instructorName != null ? instructorName : instructorId;

		if (!startTime.isBefore(endTime)) {
			throw new IllegalArgumentException("Start time must be before end time.");
		}
	}

	public boolean isUnavailable() {
		return status == AvailabilityStatus.UNAVAILABLE;
	}

	public boolean isPrefer() {
		return status == AvailabilityStatus.PREFER;
	}
}
