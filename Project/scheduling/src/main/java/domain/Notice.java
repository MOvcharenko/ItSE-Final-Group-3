package domain;

import java.time.LocalDateTime;

/**
 * Notice — notification to one user (TR2.11, UC-18).
 * {@code sectionId}/{@code termId} let the calendar link a notice to the change that caused it.
 */
public class Notice {

	private final String noticeId;
	private final String recipientUserId;
	private final NoticeEvent triggerEvent;
	private final String message;
	private final String sectionId;
	private final String termId;
	private final LocalDateTime createdAt;
	private boolean read;

	public Notice(String noticeId, String recipientUserId, NoticeEvent triggerEvent, String message,
			String sectionId, String termId, LocalDateTime createdAt) {
		this.noticeId = noticeId;
		this.recipientUserId = recipientUserId;
		this.triggerEvent = triggerEvent;
		this.message = message;
		this.sectionId = sectionId;
		this.termId = termId;
		this.createdAt = createdAt;
	}

	public void markAsRead() { this.read = true; }

	public String noticeId() { return noticeId; }
	public String recipientUserId() { return recipientUserId; }
	public NoticeEvent triggerEvent() { return triggerEvent; }
	public String message() { return message; }
	public String sectionId() { return sectionId; }
	public String termId() { return termId; }
	public LocalDateTime createdAt() { return createdAt; }
	public boolean isRead() { return read; }

}
