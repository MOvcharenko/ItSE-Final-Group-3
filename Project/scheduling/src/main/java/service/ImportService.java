package service;

import domain.ImportType;
import service.view.ImportBatchView;

/** CSV bulk import (FR3.3-FR3.7, TR4.6). */
public interface ImportService {

	/** The exact header line for the type, as in User's Guide section 5 (FR3.5). */
	String getTemplate(ImportType type);

	/**
	 * Wrong header: throws BusinessRuleException and processes no rows (UC-12 5a).
	 * Otherwise every row gets its own ADDED / NEEDS_REVIEW outcome (FR3.6). A ready-made schedule is
	 * handed to the timetable and conflict-checked at once (FR3.7).
	 */
	ImportBatchView importFile(String fileName, ImportType type, String content);
}
