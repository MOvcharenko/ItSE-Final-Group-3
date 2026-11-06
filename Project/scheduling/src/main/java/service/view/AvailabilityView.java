package service.view;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import domain.AvailabilityStatus;

/**
 * An instructor's availability blocks, equipment checklist, notes and hour summary for a term (UC-14, TR2.7).
 */
public record AvailabilityView(
		String termId,
		String instructorId,
		String instructorName,
		boolean readOnly,
		List<BlockView> blocks,
		List<String> equipmentNeeds,
		String notes,
		LocalDateTime submittedAt,
		Summary summary
) {

	public record BlockView(
			String id,
			DayOfWeek dayOfWeek,
			LocalTime startTime,
			LocalTime endTime,
			AvailabilityStatus status
	) {
	}

	public record Summary(
			double openHours,
			double preferHours,
			double unavailableHours,
			double totalHours
	) {
	}
}
