package core.importing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import domain.ImportType;

/**
 * The exact header of each import template (User's Guide section 5, FR3.5). A file is checked against this
 * before any row is looked at (UC-12 5a, TC-FR3.5). Names are compared ignoring case and surrounding spaces,
 * the order must match the template.
 */
public final class ImportColumns {

	private ImportColumns() {
	}

	public static List<String> of(ImportType type) {
		return switch (type) {
			case READY_MADE_SCHEDULE -> List.of("subject_code", "title", "days", "start_time", "end_time",
					"room", "instructor", "term");
			case SUBJECT_LIST -> List.of("subject_code", "title", "credit_hours", "default_room_type",
					"equipment_needed");
			case ROOMS -> List.of("room_name", "building", "capacity", "room_type", "equipment");
			case EQUIPMENT -> List.of("item_name", "type", "units", "home_location");
		};
	}

	/** The header line a template file starts with, e.g. "room_name,building,capacity,room_type,equipment". */
	public static String headerLine(ImportType type) {
		return String.join(",", of(type));
	}

	public static String displayName(ImportType type) {
		return switch (type) {
			case READY_MADE_SCHEDULE -> "ready-made schedule";
			case SUBJECT_LIST -> "subject list";
			case ROOMS -> "rooms";
			case EQUIPMENT -> "equipment";
		};
	}

	/** Empty when the header matches; otherwise a plain-language message for the Scheduler. */
	public static Optional<String> checkHeader(ImportType type, List<String> found) {
		List<String> expected = of(type);
		List<String> normalised = found.stream().map(ImportColumns::normalise).toList();
		if (normalised.equals(expected)) {
			return Optional.empty();
		}
		return Optional.of("The header row does not match the " + displayName(type) + " template. Expected: "
				+ String.join(", ", expected) + ". Found: " + String.join(", ", found.stream().map(String::trim).toList())
				+ ". Download the template and paste your data under its header row.");
	}

	private static String normalise(String cell) {
		return cell.trim().toLowerCase(Locale.ROOT);
	}
}
