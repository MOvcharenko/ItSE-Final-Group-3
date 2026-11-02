package service;

import domain.NoticeEvent;

/**
 * Cross-stream contract (owner: Dev A). Takes ids, so callers do not hand entities across streams.
 */
public interface NoticeService {

	/** Notify students and instructors affected by a change to a published section. */
	void notifyAffected(String sectionId, NoticeEvent event);

	/** Notify the person who submitted a booking request about a decision on it. */
	void notifyRequester(String bookingId, NoticeEvent event);

}
