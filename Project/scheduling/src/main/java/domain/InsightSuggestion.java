package domain;

/**
 * InsightSuggestion — advisory utilization/capacity suggestion, kept visibly separate
 * from conflicts (TR2.9, NFR4.4).
 */
public class InsightSuggestion {

	private final String suggestionId;
	private final InsightAxis axis;
	private final String message;
	private final String termId;
	private final String targetId; // roomId or instructorId
	private boolean dismissed;

	public InsightSuggestion(String suggestionId, InsightAxis axis, String message, String termId, String targetId) {
		this.suggestionId = suggestionId;
		this.axis = axis;
		this.message = message;
		this.termId = termId;
		this.targetId = targetId;
	}

	public void dismiss() { this.dismissed = true; }

	public String suggestionId() { return suggestionId; }
	public InsightAxis axis() { return axis; }
	public String message() { return message; }
	public String termId() { return termId; }
	public String targetId() { return targetId; }
	public boolean isDismissed() { return dismissed; }

}
