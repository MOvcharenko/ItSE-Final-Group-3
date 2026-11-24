package service.view;

import java.util.List;

import core.solver.AutoScheduleResult;

/**
 * Response view returned by {@code POST /api/terms/{id}/auto-schedule/preview}.
 */
public record AutoScheduleView(
		String termId,
		boolean solvable,
		String status,
		List<AutoScheduleResult.ProposedPlacement> placements,
		AutoScheduleResult.QualitySummary summary,
		List<String> diagnostics
) {
	public static AutoScheduleView fromResult(String termId, AutoScheduleResult result) {
		return new AutoScheduleView(
				termId,
				result.solvable(),
				result.status(),
				result.placements(),
				result.summary(),
				result.diagnostics()
		);
	}
}
