package persistence;

import domain.RowOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** One line of an imported file and its own outcome (FR3.6, table import_row). Lives inside an ImportBatchEntity. */
@Embeddable
public class ImportRowRecord {

	@Column(name = "row_no", nullable = false)
	private int rowNo;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, length = 12)
	private RowOutcome outcome;

	@Column(name = "reason", length = 500)
	private String reason;

	protected ImportRowRecord() {
	}

	public ImportRowRecord(int rowNo, RowOutcome outcome, String reason) {
		this.rowNo = rowNo;
		this.outcome = outcome;
		this.reason = reason;
	}

	public int getRowNo() {
		return rowNo;
	}

	public RowOutcome getOutcome() {
		return outcome;
	}

	public String getReason() {
		return reason;
	}
}
