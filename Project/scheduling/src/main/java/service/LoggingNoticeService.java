package service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import domain.NoticeEvent;

/**
 * Day-1 stub that only logs.
 * TODO(Dev A, Day 5): replace with the persisted implementation (UC-18).
 */
@Service
public class LoggingNoticeService implements NoticeService {

	private static final Logger log = LoggerFactory.getLogger(LoggingNoticeService.class);

	@Override
	public void notifyAffected(Long sectionId, NoticeEvent event) {
		log.info("Notice (stub): section {} {}", sectionId, event);
	}

	@Override
	public void notifyRequester(Long bookingId, NoticeEvent event) {
		log.info("Notice (stub): booking {} {}", bookingId, event);
	}

}
