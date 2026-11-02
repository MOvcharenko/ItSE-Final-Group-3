package core.importing;

import java.util.ArrayList;
import java.util.List;

/**
 * Small RFC 4180 reader: commas inside double quotes stay in the field ("AV Storage, Hale Hall"),
 * a doubled quote inside quotes is a literal quote, LF / CRLF / CR all end a row, blank lines are skipped
 * and a leading byte-order mark is ignored. Cells are returned untrimmed; the row parsers trim them.
 * No Spring, no JPA.
 */
public final class CsvParser {

	private CsvParser() {
	}

	public static List<List<String>> parse(String content) {
		String text = content.startsWith("\uFEFF") ? content.substring(1) : content;
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean inQuotes = false;
		boolean quoted = false; // current field started with a quote
		int line = 1;
		int quoteLine = 0;
		int n = text.length();

		for (int i = 0; i < n; i++) {
			char ch = text.charAt(i);
			if (inQuotes) {
				if (ch == '"') {
					if (i + 1 < n && text.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					if (ch == '\n') {
						line++;
					}
					field.append(ch);
				}
				continue;
			}
			switch (ch) {
				case '"' -> {
					if (quoted) {
						throw new CsvFormatException(line, "unexpected text after a closing quote. "
								+ "Double any quote that is part of a value.");
					}
					if (field.toString().isBlank()) {
						field.setLength(0); // spaces before an opening quote are ignored
						inQuotes = true;
						quoted = true;
						quoteLine = line;
					} else {
						field.append(ch); // stray quote inside plain text: keep it
					}
				}
				case ',' -> {
					row.add(field.toString());
					field.setLength(0);
					quoted = false;
				}
				case '\r', '\n' -> {
					if (ch == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
						i++;
					}
					row.add(field.toString());
					addUnlessBlank(rows, row, quoted);
					row = new ArrayList<>();
					field.setLength(0);
					quoted = false;
					line++;
				}
				default -> {
					if (quoted) {
						if (!Character.isWhitespace(ch)) {
							throw new CsvFormatException(line, "unexpected text after a closing quote. "
									+ "Double any quote that is part of a value.");
						}
					} else {
						field.append(ch);
					}
				}
			}
		}
		if (inQuotes) {
			throw new CsvFormatException(quoteLine, "a quoted value is never closed. "
					+ "Wrap a value that contains a comma in double quotes and close the quote.");
		}
		if (field.length() > 0 || quoted || !row.isEmpty()) {
			row.add(field.toString());
			addUnlessBlank(rows, row, quoted);
		}
		return rows;
	}

	private static void addUnlessBlank(List<List<String>> rows, List<String> row, boolean lastQuoted) {
		boolean blank = row.size() == 1 && row.get(0).isBlank() && !lastQuoted;
		if (!blank) {
			rows.add(row);
		}
	}
}
