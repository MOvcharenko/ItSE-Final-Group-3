package service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Input rules shared by the services. Messages are plain language and are shown to the Scheduler as they are:
 * on the API as a 422, and as the "Needs review" reason of an import row (FR3.6, NFR4.1).
 */
final class Check {

	private Check() {
	}

	static String text(String value, String label, int max) {
		String t = value == null ? "" : value.trim();
		if (t.isEmpty()) {
			throw new BusinessRuleException(label + " is required.");
		}
		if (t.length() > max) {
			throw new BusinessRuleException(label + " is too long (" + max + " characters at most).");
		}
		return t;
	}

	/** Blank means "not given" (null). */
	static String optional(String value, String label, int max) {
		String t = value == null ? "" : value.trim();
		if (t.isEmpty()) {
			return null;
		}
		if (t.length() > max) {
			throw new BusinessRuleException(label + " is too long (" + max + " characters at most).");
		}
		return t;
	}

	static int atLeastOne(int value, String label) {
		if (value < 1) {
			throw new BusinessRuleException(label + " must be at least 1.");
		}
		return value;
	}

	static <T> T required(T value, String label) {
		if (value == null) {
			throw new BusinessRuleException(label + " is required.");
		}
		return value;
	}

	/** Trimmed, no blanks, no duplicates, order kept. */
	static Set<String> items(Collection<String> raw, String label, int maxEach) {
		Set<String> out = new LinkedHashSet<>();
		if (raw != null) {
			for (String s : raw) {
				String t = optional(s, label, maxEach);
				if (t != null) {
					out.add(t);
				}
			}
		}
		return out;
	}

	static String limit(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max);
	}
}
