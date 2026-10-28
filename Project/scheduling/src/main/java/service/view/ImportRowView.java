package service.view;

import domain.RowOutcome;

/** reason is always set for NEEDS_REVIEW and may be null for ADDED (FR3.6). */
public record ImportRowView(int rowNumber, RowOutcome outcome, String reason) {
}
