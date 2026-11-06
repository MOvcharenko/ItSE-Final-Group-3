package service.view;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import domain.AvailabilityStatus;

/**
 * Overlay data for Timetable Builder to display an instructor's availability blocks (FR1.4).
 */
public record AvailabilityLensView(
		String instructorId,
		String instructorName,
		String termId,
		List<LensBlock> blocks
) {

	public record LensBlock(
			DayOfWeek dayOfWeek,
			LocalTime startTime,
			LocalTime endTime,
			AvailabilityStatus status
	) {
	}
}
