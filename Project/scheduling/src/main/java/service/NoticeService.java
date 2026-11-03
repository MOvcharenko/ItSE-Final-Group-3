package service;

import java.util.List;
import java.util.Set;

import domain.NoticeEvent;
import service.view.NoticeView;

/**
 * Cross-stream contract (owner: Dev A). Takes ids, so callers do not hand entities across streams. The notify methods
 * save in the caller's transaction, so a change that rolls back leaves no notice behind. Enrolled students are the one
 * exception: their notices follow on another thread after the commit, because finding them is a remote call.
 */
public interface NoticeService {

	/**
	 * Notify the section's instructors and enrolled students about a change to a published section. Takes
	 * SECTION_CHANGED, SECTION_CANCELLED or INSTRUCTOR_REASSIGNED. Call it before deleting a section.
	 */
	void notifyAffected(String sectionId, NoticeEvent event);

	/** Tell instructors who were taken off a section, since they are no longer among those affected. */
	void notifyRemovedInstructors(String sectionId, Set<String> instructorIds);

	/** Notify the requester about a decision. Takes BOOKING_APPROVED, BOOKING_DECLINED or BOOKING_CANCELLED. */
	void notifyRequester(String bookingId, NoticeEvent event);

	/** The signed-in user's own notices, newest first. */
	List<NoticeView> listMine();

	/** Answers 404 for someone else's notice, so it does not reveal that the notice exists. */
	NoticeView markRead(String noticeId);

}
