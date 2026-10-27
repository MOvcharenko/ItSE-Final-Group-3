package domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ImportBatch — one uploaded import file and its per-row outcomes (TR2.10, FR3.6).
 */
public class ImportBatch {

	private final String batchId;
	private final String fileName;
	private final ImportType importType;
	private final LocalDateTime importedAt;
	private final String schedulerId;
	private final List<ImportRow> rows = new ArrayList<>();

	public ImportBatch(String batchId, String fileName, ImportType importType, String schedulerId,
			LocalDateTime importedAt) {
		this.batchId = batchId;
		this.fileName = fileName;
		this.importType = importType;
		this.schedulerId = schedulerId;
		this.importedAt = importedAt;
	}

	public void addRowOutcome(int rowNum, RowOutcome outcome, String reason) {
		rows.add(new ImportRow(rowNum, outcome, reason));
	}

	public String batchId() { return batchId; }
	public String fileName() { return fileName; }
	public ImportType importType() { return importType; }
	public LocalDateTime importedAt() { return importedAt; }
	public String schedulerId() { return schedulerId; }
	public List<ImportRow> rows() { return List.copyOf(rows); }

}
