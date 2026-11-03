package persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import domain.NoticeEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A notice to one user (TR2.11, table notice). The ids are plain columns, because students are not in app_user and a
 * notice outlives its section: the database empties section_id and term_id when those are deleted.
 * The event is stored as text because the table has no CHECK listing the values.
 */
@Entity
@Table(name = "notice")
public class NoticeEntity extends AssignedIdEntity {

	@Column(name = "recipient_id", nullable = false, length = 100)
	private String recipientId;

	@Column(name = "term_id", length = 36)
	private String termId;

	@Column(name = "section_id", length = 36)
	private String sectionId;

	@Column(name = "trigger_event", nullable = false, length = 50)
	private String triggerEvent;

	@Column(name = "message", nullable = false, length = 1000)
	private String message;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "is_read", nullable = false)
	private boolean read;

	protected NoticeEntity() {
	}

	public NoticeEntity(String id, String recipientId, String termId, String sectionId, NoticeEvent event,
			String message, LocalDateTime createdAt) {
		super(id);
		this.recipientId = recipientId;
		this.termId = termId;
		this.sectionId = sectionId;
		this.triggerEvent = event.name();
		this.message = message;
		this.createdAt = createdAt;
	}

	public static NoticeEntity create(String recipientId, String termId, String sectionId, NoticeEvent event,
			String message, LocalDateTime createdAt) {
		return new NoticeEntity(UUID.randomUUID().toString(), recipientId, termId, sectionId, event, message, createdAt);
	}

	public void markRead() {
		this.read = true;
	}

	public String getRecipientId() {
		return recipientId;
	}

	public String getTermId() {
		return termId;
	}

	public String getSectionId() {
		return sectionId;
	}

	public NoticeEvent getTriggerEvent() {
		return NoticeEvent.valueOf(triggerEvent);
	}

	public String getMessage() {
		return message;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public boolean isRead() {
		return read;
	}
}
