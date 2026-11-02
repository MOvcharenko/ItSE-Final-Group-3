package core.importing;

import java.util.Locale;

/**
 * Matching key for looking names up across a file and the catalog: case and spacing do not matter, so the
 * template example term "Fall2026" finds the term "Fall 2026", and "founders 110" finds "Founders 110".
 * Use it for comparison only, never for what gets stored.
 */
public final class Keys {

	private Keys() {
	}

	public static String of(String name) {
		return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}
}
