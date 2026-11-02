package core.importing;

import java.util.List;

/**
 * A whole file after syntax checks. If {@code fileError} is set, the file was rejected and {@code rows} is empty
 * (wrong header, empty file, broken quoting: UC-12 5a). Otherwise every row has its own result, so a partly faulty
 * file is still useful (FR3.6, TR4.6).
 */
public record ParsedFile<T>(String fileError, List<RowResult<T>> rows) {

	public ParsedFile {
		rows = List.copyOf(rows);
	}

	public static <T> ParsedFile<T> rejected(String message) {
		return new ParsedFile<>(message, List.of());
	}

	public boolean isRejected() {
		return fileError != null;
	}
}
