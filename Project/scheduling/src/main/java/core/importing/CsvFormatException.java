package core.importing;

/** The file is not readable as CSV at all (e.g. a quote that is never closed). The whole file is rejected (UC-12). */
public class CsvFormatException extends RuntimeException {

	public CsvFormatException(int line, String message) {
		super("Line " + line + ": " + message);
	}
}
