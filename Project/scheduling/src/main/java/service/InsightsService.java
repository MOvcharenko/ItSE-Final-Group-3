package service;

import service.view.InsightView;

/**
 * Service calculating advisory suggestions and confirmations for instructor/room utilization (FR5, UC-13).
 */
public interface InsightsService {

	/**
	 * Calculates and returns advisory utilization metrics and suggestion cards for the term.
	 */
	InsightView getInsights(String termId);

	/**
	 * Dismisses an advisory suggestion card (FR5.3). Does not alter the schedule.
	 */
	void dismissSuggestion(String suggestionId);
}
