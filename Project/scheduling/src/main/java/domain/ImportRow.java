package domain;

/**
 * ImportRow — outcome of one row of an import file (TR2.10, FR3.6).
 */
public record ImportRow(int rowNumber, RowOutcome outcome, String reason) {
}
