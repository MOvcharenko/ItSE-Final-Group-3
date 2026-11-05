package persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import domain.ConflictType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Record of who dismissed which conflict, when and why (NFR3.3, table audit_log_entry).
 * Deliberately not a foreign key to conflict, so the audit record outlives a deleted term or conflict.
 */
@Entity
@Table(name = "audit_log_entry")
public class AuditLogEntryEntity extends AssignedIdEntity {

	@Column(name = "conflict_id", nullable = false, length = 36)
	private String conflictId;

	@Column(name = "conflict_type", nullable = false, length = 30)
	private String conflictType;

	@Column(name = "conflict_explanation", nullable = false, length = 1000)
	private String conflictExplanation;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUserEntity user;

	@Column(name = "occurred_at", nullable = false)
	private LocalDateTime occurredAt;

	@Column(name = "reason", nullable = false, length = 500)
	private String reason;

	protected AuditLogEntryEntity() {
	}

	public AuditLogEntryEntity(String id, String conflictId, String conflictType,
			String conflictExplanation, AppUserEntity user, LocalDateTime occurredAt, String reason) {
		super(id);
		this.conflictId = conflictId;
		this.conflictType = conflictType;
		this.conflictExplanation = conflictExplanation;
		this.user = user;
		this.occurredAt = occurredAt;
		this.reason = reason;
	}

	public static AuditLogEntryEntity record(String conflictId, ConflictType conflictType,
			String conflictExplanation, AppUserEntity user, LocalDateTime occurredAt, String reason) {
		return new AuditLogEntryEntity(UUID.randomUUID().toString(), conflictId, conflictType.name(),
				conflictExplanation, user, occurredAt, reason);
	}

	public String getConflictId() {
		return conflictId;
	}

	public ConflictType getConflictType() {
		return conflictType != null ? ConflictType.valueOf(conflictType) : null;
	}

	public String getConflictTypeString() {
		return conflictType;
	}

	public String getConflictExplanation() {
		return conflictExplanation;
	}

	public AppUserEntity getUser() {
		return user;
	}

	public LocalDateTime getOccurredAt() {
		return occurredAt;
	}

	public String getReason() {
		return reason;
	}
}
