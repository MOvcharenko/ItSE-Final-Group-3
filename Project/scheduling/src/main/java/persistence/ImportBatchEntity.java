package persistence;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import domain.ImportType;
import domain.RowOutcome;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** The record of one file import (TR2.10, tables import_batch and import_row). */
@Entity
@Table(name = "import_batch")
public class ImportBatchEntity extends AssignedIdEntity {

	@Column(name = "file_name", nullable = false, length = 255)
	private String fileName;

	@Enumerated(EnumType.STRING)
	@Column(name = "import_type", nullable = false, length = 25)
	private ImportType importType;

	@Column(name = "imported_at", nullable = false)
	private LocalDateTime importedAt;

	@Column(name = "imported_by", nullable = false, length = 100)
	private String importedBy;

	@ElementCollection
	@CollectionTable(name = "import_row", joinColumns = @JoinColumn(name = "batch_id"))
	@OrderBy("rowNo")
	private List<ImportRowRecord> rows = new ArrayList<>();

	protected ImportBatchEntity() {
	}

	public ImportBatchEntity(String id, String fileName, ImportType importType, LocalDateTime importedAt,
			String importedBy) {
		super(id);
		this.fileName = fileName;
		this.importType = importType;
		this.importedAt = importedAt;
		this.importedBy = importedBy;
	}

	public static ImportBatchEntity create(String fileName, ImportType importType, LocalDateTime importedAt,
			String importedBy) {
		return new ImportBatchEntity(UUID.randomUUID().toString(), fileName, importType, importedAt, importedBy);
	}

	public void addRow(int rowNo, RowOutcome outcome, String reason) {
		rows.add(new ImportRowRecord(rowNo, outcome, reason));
	}

	public long countByOutcome(RowOutcome outcome) {
		return rows.stream().filter(r -> r.getOutcome() == outcome).count();
	}

	public String getFileName() {
		return fileName;
	}

	public ImportType getImportType() {
		return importType;
	}

	public LocalDateTime getImportedAt() {
		return importedAt;
	}

	public String getImportedBy() {
		return importedBy;
	}

	public List<ImportRowRecord> getRows() {
		return rows;
	}
}
