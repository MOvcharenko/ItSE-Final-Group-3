package core.importing;

/** Outcome of one data row: a typed value ("Added" candidate) or the reason it "Needs review" (FR3.6). */
public sealed interface RowResult<T> {

	/** 1-based data row number, the header row is not counted. */
	int rowNumber();

	record Valid<T>(int rowNumber, T value) implements RowResult<T> {
	}

	record Invalid<T>(int rowNumber, String reason) implements RowResult<T> {
	}
}
