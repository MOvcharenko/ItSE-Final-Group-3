package core.solver;

import java.util.List;

import domain.EquipmentNeed;
import domain.MeetingSlot;

/**
 * Output from {@link TimetableSatSolver} (framework-free).
 * Status is one of OPTIMAL, FEASIBLE, UNSAT, NO_MORE_OPTIONS.
 */
public record AutoScheduleResult(
		boolean solvable,
		String status,
		List<ProposedPlacement> placements,
		QualitySummary summary,
		List<String> diagnostics
) {

	public AutoScheduleResult {
		placements = placements == null ? List.of() : List.copyOf(placements);
		diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
	}

	public record ProposedPlacement(
			String subjectId,
			String courseCode,
			String subjectTitle,
			String sectionNumber,
			String roomId,
			String roomName,
			int roomCapacity,
			String instructorId,
			String instructorName,
			String patternId,
			String patternLabel,
			List<MeetingSlot> meetings,
			List<EquipmentNeed> equipment,
			boolean matchedPreference,
			int roomFillPercent
	) {
		public ProposedPlacement {
			meetings = meetings == null ? List.of() : List.copyOf(meetings);
			equipment = equipment == null ? List.of() : List.copyOf(equipment);
		}
	}

	public record QualitySummary(
			int totalPlaced,
			int preferredSlotsMatched,
			int edgeHourSections,
			int splitDayCount,
			int maxInstructorDailySpanHours,
			int convenienceScore,
			int solverPenalty,
			long solveTimeMs,
			int satVariables,
			int satClauses
	) {
		public static QualitySummary empty(long solveTimeMs, int vars, int clauses) {
			return new QualitySummary(0, 0, 0, 0, 0, 100, 0, solveTimeMs, vars, clauses);
		}
	}
}
