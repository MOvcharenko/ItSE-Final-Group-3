package service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import domain.NoticeEvent;
import integration.SisClient;
import persistence.NoticeEntity;
import persistence.NoticeRepository;
import persistence.SectionRepository;

/**
 * Tells enrolled students about a change, on its own thread once the change has committed. The SIS is a remote call
 * that can be slow, so it must not run while the change holds the term lock and a database connection, and if the
 * change rolls back nothing is sent.
 */
@Component
public class StudentNoticeSender {

	private static final Logger log = LoggerFactory.getLogger(StudentNoticeSender.class);

	/** alreadyTold are the instructors, who have their notice and may also be enrolled. */
	public record Request(String sectionId, String termId, NoticeEvent event, String message, Set<String> alreadyTold) {
	}

	private final SisClient sis;
	private final NoticeRepository notices;
	private final SectionRepository sections;
	private final TransactionTemplate transaction;
	private final Clock clock;

	public StudentNoticeSender(SisClient sis, NoticeRepository notices, SectionRepository sections,
			PlatformTransactionManager transactions, Clock clock) {
		this.sis = sis;
		this.notices = notices;
		this.sections = sections;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	@Async
	@TransactionalEventListener
	public void send(Request request) {
		Set<String> students = enrolledStudents(request.sectionId());
		students.removeAll(request.alreadyTold());
		if (students.isEmpty()) {
			return;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		transaction.executeWithoutResult(status -> {
			// A cancelled section is gone by now, and the notice outlives it.
			String sectionId = sections.existsById(request.sectionId()) ? request.sectionId() : null;
			for (String student : students) {
				notices.save(NoticeEntity.create(student, request.termId(), sectionId, request.event(), request.message(), now));
			}
		});
	}

	/** If the SIS is down, the change has still saved and instructors have still been told (UC-06 3a). */
	private Set<String> enrolledStudents(String sectionId) {
		try {
			return new LinkedHashSet<>(sis.enrolledStudentIds(sectionId));
		} catch (RuntimeException e) {
			log.warn("SIS unavailable: students of section {} were not notified", sectionId, e);
			return new LinkedHashSet<>();
		}
	}

}
