package service.view;

import java.time.LocalDateTime;

import domain.ConflictType;

/** One entry of the dismissal audit log (NFR3.3): who dismissed which conflict, when and why. */
public record DismissalView(String id, String conflictId, ConflictType conflictType, String explanation,
		String dismissedById, String dismissedByName, LocalDateTime dismissedAt, String reason) {
}
