package team3.scheduling.service;

import team3.scheduling.domain.NoticeEvent;

/**
 * Cross-stream contract (owner: Dev A). Takes ids rather than entities until the
 * Section and Booking entities exist.
 */
public interface NoticeService {

	/** Notify students and instructors affected by a change to a published section. */
	void notifyAffected(Long sectionId, NoticeEvent event);

	/** Notify the person who submitted a booking request about a decision on it. */
	void notifyRequester(Long bookingId, NoticeEvent event);

}
