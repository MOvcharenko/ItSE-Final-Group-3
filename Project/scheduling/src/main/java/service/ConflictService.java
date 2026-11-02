package service;

/**
 * Cross-stream contract (owner: Dev B). Detection only: an open conflict never stops a Draft from being saved.
 */
public interface ConflictService {

	/** Re-runs detection for the term after a change. Runs in the caller's transaction. */
	void recheck(String termId, Ref changed);

	/** How many High-severity conflicts are still open, for the publish confirmation (FR4.7). */
	int countOpenHigh(String termId);

	/** What changed. Dev B: add booking and availability factories here. */
	record Ref(String type, String id) {

		public static Ref section(String sectionId) {
			return new Ref("SECTION", sectionId);
		}

	}

}
