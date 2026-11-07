package service.view;

import java.time.LocalDateTime;
import java.util.List;

import domain.InsightAxis;
import domain.InsightMeasure;

/**
 * Advisory optimization suggestion or positive confirmation (FR5, UC-13, NFR4.4).
 */
public record InsightView(
		String termId,
		List<InstructorInsight> instructors,
		List<RoomInsight> rooms,
		List<SuggestionCard> suggestions
) {

	public record InstructorInsight(
			String instructorId,
			String instructorName,
			double scheduledHours,
			double declaredHours,
			double ratioPercent,
			String status // UNDERUSED, HEALTHY, CONFIRMATION
	) {
	}

	public record RoomInsight(
			String roomId,
			String roomName,
			int capacity,
			double scheduledHours,
			double occupancyPercent,
			double fillPercent,
			String status // UNDERUSED, OVERSIZED, WELL_UTILIZED
	) {
	}

	public record SuggestionCard(
			String id,
			InsightAxis axis,
			InsightMeasure measure,
			String resourceId,
			String resourceName,
			String message,
			boolean dismissed,
			boolean alreadyFlagged,
			String relatedConflictId,
			LocalDateTime createdAt
	) {
	}
}
