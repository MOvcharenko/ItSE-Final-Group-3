package service.view;

import java.time.LocalDateTime;
import java.util.List;

import domain.ImportType;

public record ImportBatchView(String batchId, String fileName, ImportType importType, LocalDateTime importedAt,
		int added, int needsReview, List<ImportRowView> rows) {
}
