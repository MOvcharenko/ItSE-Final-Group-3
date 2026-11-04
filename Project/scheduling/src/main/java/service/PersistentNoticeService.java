package service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import core.NoticeMessages;
import domain.NoticeEvent;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.NoticeEntity;
import persistence.NoticeRepository;
import persistence.RoomEntity;
import persistence.SectionEntity;
import persistence.SectionRepository;
import security.CurrentUserProvider;
import service.view.NoticeView;

/** Notices to affected students, instructors and booking requesters (UC-18). */
@Service
@Transactional
public class PersistentNoticeService implements NoticeService {

	private final NoticeRepository notices;
	private final SectionRepository sections;
	private final BookingRepository bookings;
	private final AppUserRepository users;
	private final ApplicationEventPublisher events;
	private final CurrentUserProvider currentUser;
	private final Clock clock;

	public PersistentNoticeService(NoticeRepository notices, SectionRepository sections, BookingRepository bookings,
			AppUserRepository users, ApplicationEventPublisher events, CurrentUserProvider currentUser, Clock clock) {
		this.notices = notices;
		this.sections = sections;
		this.bookings = bookings;
		this.users = users;
		this.events = events;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Override
	public void notifyAffected(String sectionId, NoticeEvent event) {
		SectionEntity section = section(sectionId);
		String message = NoticeMessages.fit(sectionMessage(section, event));
		Set<String> instructors = section.getInstructorIds();
		saveFor(instructors, section, event, message);
		events.publishEvent(
				new StudentNoticeSender.Request(sectionId, section.getTerm().getId(), event, message, instructors));
	}

	@Override
	public void notifyRemovedInstructors(String sectionId, Set<String> instructorIds) {
		SectionEntity section = section(sectionId);
		saveFor(instructorIds, section, NoticeEvent.INSTRUCTOR_REASSIGNED,
				NoticeMessages.fit(NoticeMessages.noLongerTeaching(label(section))));
	}

	@Override
	public void notifyRequester(String bookingId, NoticeEvent event) {
		BookingEntity booking = bookings.findById(bookingId)
				.orElseThrow(() -> NotFoundException.of("Booking", bookingId));
		notices.save(NoticeEntity.create(booking.getRequester().getId(), null, null, event,
				NoticeMessages.fit(bookingMessage(booking, event)), LocalDateTime.now(clock)));
	}

	@Override
	@Transactional(readOnly = true)
	public List<NoticeView> listMine() {
		return notices.findByRecipientIdOrderByCreatedAtDesc(currentUser.get().id()).stream()
				.map(PersistentNoticeService::toView).toList();
	}

	@Override
	public NoticeView markRead(String noticeId) {
		NoticeEntity notice = notices.findByIdAndRecipientId(noticeId, currentUser.get().id())
				.orElseThrow(() -> NotFoundException.of("Notice", noticeId));
		notice.markRead();
		return toView(notice);
	}

	private void saveFor(Set<String> recipients, SectionEntity section, NoticeEvent event, String fittedMessage) {
		LocalDateTime now = LocalDateTime.now(clock);
		for (String recipient : recipients) {
			notices.save(NoticeEntity.create(recipient, section.getTerm().getId(), section.getId(), event, fittedMessage, now));
		}
	}

	private SectionEntity section(String sectionId) {
		return sections.findById(sectionId).orElseThrow(() -> NotFoundException.of("Section", sectionId));
	}

	private String sectionMessage(SectionEntity section, NoticeEvent event) {
		return switch (event) {
			case SECTION_CHANGED -> {
				RoomEntity room = section.getRoom();
				yield NoticeMessages.sectionChanged(label(section), room == null ? null : room.getName(),
						section.getMeetings(), instructorNames(section));
			}
			case SECTION_CANCELLED -> NoticeMessages.sectionCancelled(label(section));
			case INSTRUCTOR_REASSIGNED -> NoticeMessages.instructorsChanged(label(section), instructorNames(section));
			default -> throw new IllegalArgumentException(event + " is not a section event.");
		};
	}

	private List<String> instructorNames(SectionEntity section) {
		return users.findAllById(section.getInstructorIds()).stream().map(AppUserEntity::getDisplayName).toList();
	}

	private static String bookingMessage(BookingEntity booking, NoticeEvent event) {
		String resource = booking.resourceName();
		return switch (event) {
			case BOOKING_APPROVED -> NoticeMessages.bookingApproved(resource, booking.getStartTime(), booking.getEndTime());
			case BOOKING_DECLINED -> NoticeMessages.bookingDeclined(resource, booking.getStartTime(), booking.getEndTime(),
					booking.getCancelNote());
			case BOOKING_CANCELLED -> NoticeMessages.bookingCancelled(resource, booking.getStartTime(), booking.getEndTime(),
					booking.getCancelNote());
			default -> throw new IllegalArgumentException(event + " is not a booking event.");
		};
	}

	private static String label(SectionEntity section) {
		return NoticeMessages.sectionLabel(section.getSubject().getCourseCode(), section.getSectionNumber());
	}

	private static NoticeView toView(NoticeEntity notice) {
		return new NoticeView(notice.getId(), notice.getTriggerEvent(), notice.getMessage(), notice.getTermId(),
				notice.getSectionId(), notice.getCreatedAt(), notice.isRead());
	}

}
