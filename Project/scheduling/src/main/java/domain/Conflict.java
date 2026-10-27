package domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Conflict — detected schedule conflict record (TR2.8).
 */
public class Conflict {

	private final String conflictId;
	private final ConflictType type;
	private final Severity severity;
	private final String explanation;
	private ResolutionState resolutionState;
	private final List<String> sectionIds;
	private final String bookingId;
	private AuditLogEntry dismissalAudit;

	public Conflict(String conflictId, ConflictType type, Severity severity, String explanation,
			List<String> sectionIds, String bookingId) {
		this.conflictId = conflictId;
		this.type = type;
		this.severity = severity;
		this.explanation = explanation;
		this.resolutionState = ResolutionState.OPEN;
		this.sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
		this.bookingId = bookingId;
	}

	public void resolve() { this.resolutionState = ResolutionState.RESOLVED; }

	/**
	 * Dismisses the conflict and records who, when, which conflict and why (NFR3.3).
	 * {@code at} comes from the injected Clock, never from {@code now()}.
	 */
	public AuditLogEntry dismiss(User user, String reason, LocalDateTime at) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("A reason is required to dismiss a conflict.");
		}
		this.resolutionState = ResolutionState.DISMISSED;
		this.dismissalAudit = new AuditLogEntry("AUD-" + conflictId, conflictId, at, reason, user.userId());
		return dismissalAudit;
	}

	public String conflictId() { return conflictId; }
	public ConflictType type() { return type; }
	public Severity severity() { return severity; }
	public String explanation() { return explanation; }
	public ResolutionState resolutionState() { return resolutionState; }
	public List<String> sectionIds() { return sectionIds; }
	public String bookingId() { return bookingId; }
	public AuditLogEntry dismissalAudit() { return dismissalAudit; }

}
