package api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import service.InsightsService;
import service.view.InsightView;

/**
 * REST API for viewing optimization insights and dismissing suggestion cards (FR5, UC-13, NFR4.4).
 */
@RestController
public class InsightsController {

	private final InsightsService insights;

	public InsightsController(InsightsService insights) {
		this.insights = insights;
	}

	/**
	 * Returns advisory utilization bars, suggestion cards, and positive confirmations for a term (FR5.1, FR5.2).
	 */
	@GetMapping("/api/terms/{id}/insights")
	@PreAuthorize("hasRole('SCHEDULER')")
	public InsightView getInsights(@PathVariable("id") String termId) {
		return insights.getInsights(termId);
	}

	/**
	 * Dismisses an advisory suggestion card without altering the schedule (FR5.3).
	 */
	@PostMapping("/api/insights/{id}/dismiss")
	@PreAuthorize("hasRole('SCHEDULER')")
	public void dismissSuggestion(@PathVariable("id") String suggestionId) {
		insights.dismissSuggestion(suggestionId);
	}
}
