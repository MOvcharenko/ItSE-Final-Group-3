package domain;

import java.time.LocalDate;

/**
 * Term — academic term (TR2.1, NFR3.4).
 * The "warn on open High conflicts" publishing rule (FR4.7) lives in TimetableService,
 * which can ask the conflict service; this class only records the state change.
 */
public class Term {

	private final String termId;
	private String name;
	private final LocalDate startDate;
	private final LocalDate endDate;
	private TermStatus status;

	public Term(String termId, String name, LocalDate startDate, LocalDate endDate, TermStatus status) {
		if (endDate.isBefore(startDate)) {
			throw new IllegalArgumentException("The term must end on or after its start date.");
		}
		this.termId = termId;
		this.name = name;
		this.startDate = startDate;
		this.endDate = endDate;
		this.status = status;
	}

	public void publish() {
		this.status = TermStatus.PUBLISHED;
	}

	public void rename(String newName) {
		this.name = newName;
	}

	/** A copy always starts as a Draft (FR1.1). */
	public Term copy(String newTermId, String newName) {
		return new Term(newTermId, newName, startDate, endDate, TermStatus.DRAFT);
	}

	public boolean isLocked() { return status == TermStatus.PUBLISHED; }
	public String termId() { return termId; }
	public String name() { return name; }
	public LocalDate startDate() { return startDate; }
	public LocalDate endDate() { return endDate; }
	public TermStatus status() { return status; }

}
