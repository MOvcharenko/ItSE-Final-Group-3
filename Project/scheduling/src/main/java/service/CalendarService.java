package service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import core.CalendarDates;
import core.IcsWriter;
import core.NoticeMessages;
import domain.CalendarScope;
import domain.MeetingPattern;
import domain.MeetingSlot;
import domain.Role;
import integration.SisClient;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.RoomEntity;
import persistence.SectionEntity;
import persistence.SectionRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import security.CurrentUser;
import security.CurrentUserProvider;
import service.view.CalendarView;
import service.view.SectionView;

/**
 * Day, week and term calendars and .ics export (FR6, UC-15, UC-16, UC-17). What a user sees is decided here from the
 * token, never from the filters: Schedulers see every section, including Drafts unless a Published term covers the same
 * dates and no term was chosen; Instructors see what they teach and Students what they are enrolled in, in Published
 * terms only (TR3.2, TR3.3). A student's sections come from the SIS, a remote call that can be slow, so it runs before
 * the transaction starts and never holds a database connection.
 */
@Service
public class CalendarService {

	private static final Logger log = LoggerFactory.getLogger(CalendarService.class);

	private static final Comparator<CalendarView.Event> BY_TIME = Comparator.comparing(CalendarView.Event::date)
			.thenComparing(CalendarView.Event::start)
			.thenComparing(CalendarView.Event::courseCode)
			.thenComparing(CalendarView.Event::sectionNumber);
	private static final Comparator<SectionView.InstructorView> BY_NAME = Comparator.comparing(
			SectionView.InstructorView::name, Comparator.nullsLast(Comparator.naturalOrder()));

	private static final Comparator<IcsWriter.Event> BY_FIRST_MEETING = Comparator.comparing(IcsWriter.Event::date)
			.thenComparing(IcsWriter.Event::start)
			.thenComparing(IcsWriter.Event::uid);

	private static final Comparator<MeetingSlot> BY_DAY_AND_TIME = Comparator
			.comparing(MeetingSlot::day, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MeetingSlot::date, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MeetingSlot::start)
			.thenComparing(MeetingSlot::end);

	private final TermRepository terms;
	private final SectionRepository sections;
	private final AppUserRepository users;
	private final SisClient sis;
	private final CurrentUserProvider currentUser;
	private final Clock clock;
	private final TransactionTemplate readOnly;

	public CalendarService(TermRepository terms, SectionRepository sections, AppUserRepository users, SisClient sis,
			CurrentUserProvider currentUser, Clock clock, PlatformTransactionManager transactions) {
		this.terms = terms;
		this.sections = sections;
		this.users = users;
		this.sis = sis;
		this.currentUser = currentUser;
		this.clock = clock;
		this.readOnly = new TransactionTemplate(transactions);
		this.readOnly.setReadOnly(true);
	}

	/** A null date means today. A TERM view uses the chosen term, or else the one term that includes the date. */
	public CalendarView calendar(CalendarScope scope, LocalDate dateOrNull, CalendarFilter filter) {
		CurrentUser user = currentUser.get();
		LocalDate date = dateOrNull == null ? LocalDate.now(clock) : dateOrNull;
		Set<String> enrolledOrNull = enrolledOrNull(user);
		return readOnly.execute(status -> calendarView(scope, date, filter, user, enrolledOrNull));
	}

	private CalendarView calendarView(CalendarScope scope, LocalDate date, CalendarFilter filter, CurrentUser user,
			Set<String> enrolledOrNull) {
		TermEntity chosenTerm = filter.termId() == null ? null : visibleTerm(filter.termId(), user);
		if (scope == CalendarScope.TERM && chosenTerm == null) {
			chosenTerm = onlyTermIncluding(date, user);
		}
		LocalDate from = switch (scope) {
			case DAY -> date;
			case WEEK -> CalendarDates.weekStart(date);
			case TERM -> chosenTerm.getStartDate();
		};
		LocalDate to = switch (scope) {
			case DAY -> date;
			case WEEK -> CalendarDates.weekEnd(date);
			case TERM -> chosenTerm.getEndDate();
		};

		List<TermEntity> inView = chosenTerm != null ? List.of(chosenTerm) : termsWhenNoneChosen(from, to, user);
		List<SectionEntity> shown = shownSections(inView, filter, user, enrolledOrNull);
		return new CalendarView(scope, from, to, enrolledOrNull != null, eventsOf(shown, from, to));
	}

	/**
	 * One whole term as an .ics file (FR6.3), with the same term choice, filters and visibility as a TERM view. An empty
	 * file would look like having no classes, so when the SIS is down this refuses instead.
	 */
	public String exportIcs(LocalDate dateOrNull, CalendarFilter filter) {
		CurrentUser user = currentUser.get();
		LocalDate date = dateOrNull == null ? LocalDate.now(clock) : dateOrNull;
		Set<String> enrolledOrNull = enrolledOrNull(user);
		if (enrolledOrNull == null) {
			throw new ServiceUnavailableException("Your enrolled sections could not be loaded. Please try again shortly.");
		}
		List<IcsWriter.Event> events = readOnly.execute(status -> {
			TermEntity term = filter.termId() == null ? onlyTermIncluding(date, user) : visibleTerm(filter.termId(), user);
			List<SectionEntity> shown = shownSections(List.of(term), filter, user, enrolledOrNull);
			Map<String, String> names = instructorNames(shown);
			List<IcsWriter.Event> found = new ArrayList<>();
			for (SectionEntity section : shown) {
				found.addAll(icsEventsOf(section, term, names));
			}
			return found;
		});
		events.sort(BY_FIRST_MEETING);
		return IcsWriter.write(events, clock.instant());
	}

	/** Meetings are numbered within their type, in day and time order. A meeting with no date in the term is left out. */
	private static List<IcsWriter.Event> icsEventsOf(SectionEntity section, TermEntity term, Map<String, String> names) {
		List<IcsWriter.Event> events = new ArrayList<>();
		Map<MeetingPattern, Integer> numbered = new EnumMap<>(MeetingPattern.class);
		for (MeetingSlot meeting : section.getMeetings().stream().sorted(BY_DAY_AND_TIME).toList()) {
			int number = numbered.merge(meeting.pattern(), 1, Integer::sum);
			List<LocalDate> dates = CalendarDates.datesOf(meeting, term.getStartDate(), term.getEndDate(),
					term.getStartDate(), term.getEndDate());
			if (!dates.isEmpty()) {
				events.add(icsEvent(section, meeting, number, dates.get(0), term, names));
			}
		}
		return events;
	}

	/**
	 * The event ID is the section, the meeting type and its number within that type, not the meeting's time. So moving
	 * a meeting keeps its ID, and a higher SEQUENCE tells a calendar app that this version replaces the old one.
	 */
	private static IcsWriter.Event icsEvent(SectionEntity section, MeetingSlot meeting, int number, LocalDate firstDate,
			TermEntity term, Map<String, String> names) {
		String label = NoticeMessages.sectionLabel(section.getSubject().getCourseCode(), section.getSectionNumber());
		boolean weekly = meeting.pattern() == MeetingPattern.WEEKLY;
		RoomEntity room = section.getRoom();
		String instructors = section.getInstructorIds().stream().map(names::get).sorted().collect(Collectors.joining(", "));
		return new IcsWriter.Event(
				section.getId() + "-" + meeting.pattern() + "-" + number + "@academic-scheduling",
				meeting.pattern() == MeetingPattern.EXAM ? label + " (Exam)" : label,
				room == null ? null : room.getName() + ", " + room.getBuilding(),
				section.getSubject().getTitle() + "\nInstructors: " + instructors + "\nTerm: " + term.getName(),
				firstDate, meeting.start(), meeting.end(), weekly ? term.getEndDate() : null, !term.isPublished(),
				section.getRevision());
	}

	/** A student's sections, or null if the SIS did not answer. Only students need the SIS. */
	private Set<String> enrolledOrNull(CurrentUser user) {
		if (user.role() != Role.STUDENT) {
			return Set.of();
		}
		try {
			return sis.enrolledSectionIds(user.id());
		} catch (RuntimeException e) {
			log.warn("SIS unavailable: cannot show the calendar of student {}", user.id(), e);
			return null;
		}
	}

	/** The sections in these terms that match the filter and that this user's role may see. */
	private List<SectionEntity> shownSections(List<TermEntity> inView, CalendarFilter filter, CurrentUser user,
			Set<String> enrolledOrNull) {
		if (enrolledOrNull == null) {
			return List.of();
		}
		List<SectionEntity> candidates = inView.stream().flatMap(term -> sections.findForTerm(term.getId()).stream())
				.filter(matches(filter)).toList();
		return switch (user.role()) {
			case INSTRUCTOR -> candidates.stream().filter(s -> s.getInstructorIds().contains(user.id())).toList();
			case STUDENT -> candidates.stream().filter(s -> enrolledOrNull.contains(s.getId())).toList();
			case SCHEDULER -> candidates;
		};
	}

	private List<CalendarView.Event> eventsOf(List<SectionEntity> shown, LocalDate from, LocalDate to) {
		Map<String, String> names = instructorNames(shown);
		List<CalendarView.Event> events = new ArrayList<>();
		for (SectionEntity section : shown) {
			TermEntity term = section.getTerm();
			RoomEntity room = section.getRoom();
			// The id lets a calendar offer an instructor filter, which takes a user id (FR6.2).
			List<SectionView.InstructorView> instructors = section.getInstructorIds().stream()
					.map(id -> new SectionView.InstructorView(id, names.get(id)))
					.sorted(BY_NAME).toList();
			for (MeetingSlot meeting : section.getMeetings()) {
				for (LocalDate date : CalendarDates.datesOf(meeting, term.getStartDate(), term.getEndDate(), from, to)) {
					events.add(new CalendarView.Event(section.getId(), section.getSubject().getCourseCode(),
							section.getSubject().getTitle(), section.getSectionNumber(), meeting.pattern(), date, meeting.start(), meeting.end(),
							room == null ? null : room.getId(), room == null ? null : room.getName(),
							room == null ? null : room.getBuilding(), instructors, term.getId(), term.getName(),
							term.getStatus()));
				}
			}
		}
		events.sort(BY_TIME);
		return events;
	}

	private Map<String, String> instructorNames(List<SectionEntity> shown) {
		Set<String> instructorIds = shown.stream().flatMap(section -> section.getInstructorIds().stream())
				.collect(Collectors.toSet());
		return users.findAllById(instructorIds).stream()
				.collect(Collectors.toMap(AppUserEntity::getId, AppUserEntity::getDisplayName));
	}

	private static Predicate<SectionEntity> matches(CalendarFilter filter) {
		return section -> {
			RoomEntity room = section.getRoom();
			return (filter.course() == null || filter.course().equalsIgnoreCase(section.getSubject().getCourseCode()))
					&& (filter.instructorId() == null || section.getInstructorIds().contains(filter.instructorId()))
					&& (filter.roomId() == null || (room != null && filter.roomId().equals(room.getId())))
					&& (filter.building() == null || (room != null && filter.building().equalsIgnoreCase(room.getBuilding())));
		};
	}

	private static boolean canSee(TermEntity term, CurrentUser user) {
		return term.isPublished() || user.isScheduler();
	}

	/** A Draft is hidden from non-Schedulers, so to them it answers the same as a term that does not exist. */
	private TermEntity visibleTerm(String termId, CurrentUser user) {
		return terms.findById(termId).filter(term -> canSee(term, user))
				.orElseThrow(() -> NotFoundException.of("Term", termId));
	}

	/**
	 * With no term chosen, a Published term hides the Drafts on the same dates. A copied term is a Draft with its source's
	 * dates, so otherwise planning a copy would double the live calendar. A Draft can still be chosen by its id.
	 */
	private List<TermEntity> termsWhenNoneChosen(LocalDate from, LocalDate to, CurrentUser user) {
		List<TermEntity> visible = terms.findOverlapping(from, to).stream().filter(term -> canSee(term, user)).toList();
		List<TermEntity> published = visible.stream().filter(TermEntity::isPublished).toList();
		return published.isEmpty() ? visible : published;
	}

	private TermEntity onlyTermIncluding(LocalDate date, CurrentUser user) {
		List<TermEntity> found = termsWhenNoneChosen(date, date, user);
		if (found.size() != 1) {
			throw new BusinessRuleException(found.isEmpty() ? "No term includes " + date + "."
					: "More than one term includes " + date + ". Choose a term.");
		}
		return found.get(0);
	}

}
