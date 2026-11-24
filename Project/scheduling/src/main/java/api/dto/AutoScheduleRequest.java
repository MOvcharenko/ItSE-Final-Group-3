package api.dto;

import java.util.List;

/**
 * Configuration and previous-option blocking keys for SAT/MaxSAT schedule preview (Dev B).
 */
public record AutoScheduleRequest(
		List<SubjectItem> items,
		Boolean keepExistingFixed,
		Boolean avoidSplitDays,
		Boolean preferPrimeHours,
		Boolean respectPreferences,
		Boolean optimizeRoomFit,
		List<List<PlacementChoice>> excludedSolutions
) {

	public record SubjectItem(
			String subjectId,
			String sectionNumber,
			List<String> instructorIds,
			String patternStyle
	) {
	}

	public record PlacementChoice(
			String subjectId,
			String sectionNumber,
			String roomId,
			String instructorId,
			String patternId
	) {
	}
}
