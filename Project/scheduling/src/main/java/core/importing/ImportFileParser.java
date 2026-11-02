package core.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import core.importing.ImportRows.EquipmentRow;
import core.importing.ImportRows.RoomRow;
import core.importing.ImportRows.ScheduleRow;
import core.importing.ImportRows.SubjectRow;
import domain.ImportType;

/**
 * Turns the text of an uploaded CSV into per-row results (FR3.3-FR3.6, TR4.6). It checks the header against the
 * template first (a mismatch rejects the file and no row is read), then judges every row on its own.
 * It knows nothing about the catalog: "room not recognized" and "duplicate subject code" are the service's job.
 */
public final class ImportFileParser {

	private ImportFileParser() {
	}

	public static ParsedFile<RoomRow> rooms(String content) {
		return parse(content, ImportType.ROOMS, r -> {
			String name = r.text(0, 100);
			String building = r.text(1, 100);
			Integer capacity = r.positiveInt(2);
			var type = r.roomType(3);
			List<String> equipment = r.list(4, 100);
			return r.ok() ? new RoomRow(name, building, capacity, type, equipment) : null;
		});
	}

	public static ParsedFile<EquipmentRow> equipment(String content) {
		return parse(content, ImportType.EQUIPMENT, r -> {
			String name = r.text(0, 100);
			String type = r.text(1, 100);
			Integer units = r.positiveInt(2);
			String home = r.optionalText(3, 200);
			return r.ok() ? new EquipmentRow(name, type, units, home) : null;
		});
	}

	public static ParsedFile<SubjectRow> subjects(String content) {
		return parse(content, ImportType.SUBJECT_LIST, r -> {
			String code = r.text(0, 30);
			String title = r.text(1, 200);
			Integer credits = r.positiveInt(2);
			var type = r.roomType(3);
			List<String> equipment = r.list(4, 100);
			return r.ok() ? new SubjectRow(code, title, credits, type, equipment) : null;
		});
	}

	public static ParsedFile<ScheduleRow> schedule(String content) {
		return parse(content, ImportType.READY_MADE_SCHEDULE, r -> {
			String code = r.text(0, 30);
			String title = r.text(1, 200);
			var days = r.days(2);
			var start = r.time(3);
			var end = r.time(4);
			if (start != null && end != null && !start.isBefore(end)) {
				r.problem("start_time must be before end_time");
			}
			String room = r.text(5, 100);
			List<String> instructors = r.requiredList(6, 150);
			String term = r.text(7, 100);
			return r.ok() ? new ScheduleRow(code, title, days, start, end, room, instructors, term) : null;
		});
	}

	private static <T> ParsedFile<T> parse(String content, ImportType type, Function<RowReader, T> rowParser) {
		List<List<String>> table;
		try {
			table = CsvParser.parse(content);
		} catch (CsvFormatException e) {
			return ParsedFile.rejected(e.getMessage());
		}
		if (table.isEmpty()) {
			return ParsedFile.rejected("The file is empty. Download the template and fill it in.");
		}
		var headerError = ImportColumns.checkHeader(type, table.get(0));
		if (headerError.isPresent()) {
			return ParsedFile.rejected(headerError.get());
		}
		List<String> columns = ImportColumns.of(type);
		List<RowResult<T>> results = new ArrayList<>();
		for (int k = 1; k < table.size(); k++) {
			int rowNumber = k; // header is row 0, so the first data row is 1
			List<String> cells = table.get(k);
			if (cells.size() > columns.size()) {
				results.add(new RowResult.Invalid<>(rowNumber, "Expected " + columns.size() + " columns but found "
						+ cells.size() + ". If a value contains a comma, wrap it in double quotes."));
				continue;
			}
			RowReader reader = new RowReader(columns, cells); // missing trailing cells read as blank
			T value = rowParser.apply(reader);
			results.add(reader.ok() ? new RowResult.Valid<>(rowNumber, value)
					: new RowResult.Invalid<>(rowNumber, reader.reason()));
		}
		return new ParsedFile<>(null, results);
	}
}
