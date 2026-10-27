package team3.scheduling.domain;

/** What happened, for notices to affected students, instructors and requesters (UC-18). */
public enum NoticeEvent {
	SECTION_CHANGED,
	SECTION_CANCELLED,
	INSTRUCTOR_REASSIGNED,
	BOOKING_APPROVED,
	BOOKING_DECLINED,
	BOOKING_CANCELLED
}
