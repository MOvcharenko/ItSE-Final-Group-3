package service.view;

import java.time.LocalDateTime;

import domain.NoticeEvent;

/** One notice for its recipient. sectionId links it to the calendar, and is empty once the section is removed. */
public record NoticeView(String id, NoticeEvent event, String message, String termId, String sectionId,
		LocalDateTime createdAt, boolean read) {
}
