package core.importing;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import domain.RoomType;

/**
 * Reads the cells of one row and collects EVERY problem with it, so the Scheduler can fix a row in one go
 * (FR3.6). Methods return null after recording a problem; the caller checks {@link #ok()} before using values.
 */
final class RowReader {

	/** The template's example cell for "no equipment"; treat it as blank if someone copies it literally. */
	private static final String PLACEHOLDER = "(leave blank if none)";
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT);

	private final List<String> columns;
	private final List<String> cells;
	private final List<String> problems = new ArrayList<>();

	RowReader(List<String> columns, List<String> cells) {
		this.columns = columns;
		this.cells = cells;
	}

	boolean ok() {
		return problems.isEmpty();
	}

	String reason() {
		return String.join("; ", problems);
	}

	private String raw(int i) {
		return i < cells.size() ? cells.get(i).trim() : "";
	}

	String text(int i, int maxLength) {
		String v = raw(i);
		if (v.isEmpty()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		return limited(i, v, maxLength);
	}

	String optionalText(int i, int maxLength) {
		String v = raw(i);
		return v.isEmpty() ? null : limited(i, v, maxLength);
	}

	private String limited(int i, String v, int maxLength) {
		if (v.length() > maxLength) {
			problems.add(columns.get(i) + " is too long (" + maxLength + " characters at most)");
			return null;
		}
		return v;
	}

	Integer positiveInt(int i) {
		String v = raw(i);
		if (v.isEmpty()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		try {
			int n = Integer.parseInt(v);
			if (n < 1) {
				problems.add(columns.get(i) + " must be at least 1 (found '" + v + "')");
				return null;
			}
			return n;
		} catch (NumberFormatException e) {
			problems.add(columns.get(i) + " must be a whole number (found '" + v + "')");
			return null;
		}
	}

	RoomType roomType(int i) {
		String v = raw(i);
		if (v.isEmpty()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		String key = v.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
		for (RoomType t : RoomType.values()) {
			if (t.name().equals(key)) {
				return t;
			}
		}
		problems.add(columns.get(i) + " '" + v + "' is not recognised. Use Lecture hall, Lab or Seminar room");
		return null;
	}

	/** Semicolon-separated list, e.g. "Projector; Whiteboard". Blank and the template placeholder mean none. */
	List<String> list(int i, int maxItemLength) {
		String v = raw(i);
		if (v.isEmpty() || v.equalsIgnoreCase(PLACEHOLDER)) {
			return List.of();
		}
		Set<String> items = new LinkedHashSet<>();
		for (String part : v.split(";")) {
			String item = part.trim();
			if (item.isEmpty()) {
				continue;
			}
			if (item.length() > maxItemLength) {
				problems.add(columns.get(i) + " has an entry that is too long (" + maxItemLength + " characters at most)");
				return List.of();
			}
			items.add(item);
		}
		return List.copyOf(items);
	}

	List<String> requiredList(int i, int maxItemLength) {
		List<String> items = list(i, maxItemLength);
		if (items.isEmpty() && ok()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		return items.isEmpty() ? null : items;
	}

	/** Day codes M, T, W, Th, F written together: MWF, TTh, MTWThF. */
	List<DayOfWeek> days(int i) {
		String v = raw(i);
		if (v.isEmpty()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		String s = v.replaceAll("[\\s/]", "").toUpperCase(Locale.ROOT);
		List<DayOfWeek> days = new ArrayList<>();
		for (int k = 0; k < s.length(); k++) {
			char c = s.charAt(k);
			DayOfWeek d;
			switch (c) {
				case 'M' -> d = DayOfWeek.MONDAY;
				case 'W' -> d = DayOfWeek.WEDNESDAY;
				case 'F' -> d = DayOfWeek.FRIDAY;
				case 'T' -> {
					if (k + 1 < s.length() && s.charAt(k + 1) == 'H') {
						d = DayOfWeek.THURSDAY;
						k++;
					} else {
						d = DayOfWeek.TUESDAY;
					}
				}
				default -> {
					problems.add(columns.get(i) + " '" + v + "' has an unknown day code '" + c + "'. Use M, T, W, Th, F");
					return null;
				}
			}
			if (days.contains(d)) {
				problems.add(columns.get(i) + " '" + v + "' lists " + d.name().toLowerCase(Locale.ROOT) + " twice");
				return null;
			}
			days.add(d);
		}
		return List.copyOf(days);
	}

	/** 24-hour time such as 13:00 or 9:30. */
	LocalTime time(int i) {
		String v = raw(i);
		if (v.isEmpty()) {
			problems.add("Missing required field: " + columns.get(i));
			return null;
		}
		try {
			return LocalTime.parse(v, TIME);
		} catch (java.time.format.DateTimeParseException e) {
			problems.add(columns.get(i) + " '" + v + "' is not a 24-hour time such as 13:00");
			return null;
		}
	}

	void problem(String message) {
		problems.add(message);
	}
}
