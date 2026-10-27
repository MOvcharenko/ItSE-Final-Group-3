package domain;

import java.time.LocalDateTime;

/**
 * AuditLogEntry — record of a dismissed/overridden warning: who, when, which conflict, why (NFR3.3).
 */
public record AuditLogEntry(String entryId, String conflictId, LocalDateTime timestamp, String reason,
		String dismissedByUserId) {
}
