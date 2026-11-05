package service;

import java.util.List;

import api.dto.ReassignRequest;
import domain.ConflictType;
import domain.ResolutionState;
import domain.Severity;
import service.view.ConflictView;

/**
 * Cross-stream contract (owner: Dev B). Detection only: an open conflict never stops a Draft from being saved.
 */
public interface ConflictService {

	/** Re-runs detection for the term after a change. Runs in the caller's transaction. */
	void recheck(String termId, Ref changed);

	/** How many High-severity conflicts are still open, for the publish confirmation (FR4.7). */
	int countOpenHigh(String termId);

	/** Lists conflicts for a term, optionally filtered by type, severity, or resolution state. */
	List<ConflictView> listConflicts(String termId, ConflictType typeOrNull, Severity severityOrNull,
			ResolutionState stateOrNull);

	/** On-demand full re-scan of a term's schedule (FR4.2, UC-06). */
	List<ConflictView> rescan(String termId);

	/** Resolves a conflict by reassigning resource or slots directly (FR4.5, UC-05). */
	ConflictView reassign(String conflictId, ReassignRequest request);

	/** Dismisses a conflict as an accepted risk with a mandatory reason and audit entry (FR4.5, NFR3.3). */
	ConflictView dismiss(String conflictId, String reason);

	/** What changed. */
	record Ref(String type, String id) {

		public static Ref section(String sectionId) {
			return new Ref("SECTION", sectionId);
		}

		public static Ref booking(String bookingId) {
			return new Ref("BOOKING", bookingId);
		}

		public static Ref availability(String instructorId) {
			return new Ref("AVAILABILITY", instructorId);
		}

		public static Ref term(String termId) {
			return new Ref("TERM", termId);
		}

	}

}
