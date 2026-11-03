package service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import domain.EquipmentNeed;
import domain.MeetingPattern;
import domain.MeetingSlot;
import domain.NoticeEvent;
import domain.ResourceStatus;
import domain.Role;
import domain.TermStatus;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SectionEntity;
import persistence.SectionRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import security.CurrentUser;
import security.CurrentUserProvider;
import service.view.SectionView;
import service.view.TermView;

/**
 * Terms and sections (FR1, UC-02, UC-03, UC-04). Open conflicts never stop a save: detection is a safety net, not a
 * lock. Each change and its conflict re-check share one transaction, and changes to one term run one at a time.
 */
@Service
@Transactional
public class TimetableService {

	private static final String DEFAULT_SECTION_NUMBER = "01";
	private static final int MAX_SECTION_NUMBER_LENGTH = 10;

	private final TermRepository terms;
	private final SectionRepository sections;
	private final SubjectRepository subjects;
	private final RoomRepository rooms;
	private final EquipmentRepository equipment;
	private final AppUserRepository users;
	private final ConflictService conflicts;
	private final NoticeService notices;
	private final CurrentUserProvider currentUser;
	private final Clock clock;

	public TimetableService(TermRepository terms, SectionRepository sections, SubjectRepository subjects,
			RoomRepository rooms, EquipmentRepository equipment, AppUserRepository users, ConflictService conflicts,
			NoticeService notices, CurrentUserProvider currentUser, Clock clock) {
		this.terms = terms;
		this.sections = sections;
		this.subjects = subjects;
		this.rooms = rooms;
		this.equipment = equipment;
		this.users = users;
		this.conflicts = conflicts;
		this.notices = notices;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	// ---- Terms ----

	/** Students only see published terms. Instructors also see drafts, because they submit availability for them. */
	@Transactional(readOnly = true)
	public List<TermView> listTerms() {
		List<TermEntity> found = currentUser.get().role() == Role.STUDENT
				? terms.findByStatusOrderByStartDateDescNameAsc(TermStatus.PUBLISHED)
				: terms.findAllByOrderByStartDateDescNameAsc();
		return found.stream().map(TimetableService::toView).toList();
	}

	@Transactional(readOnly = true)
	public TermView getTerm(String termId) {
		TermEntity term = term(termId);
		if (currentUser.get().role() == Role.STUDENT && !term.isPublished()) {
			throw NotFoundException.of("Term", termId);
		}
		return toView(term);
	}

	public TermView createTerm(String name, LocalDate startDate, LocalDate endDate) {
		requireEditor();
		if (endDate.isBefore(startDate)) {
			throw new BusinessRuleException("The term must end on or after its start date.");
		}
		requireFreeName(name);
		return toView(terms.save(TermEntity.create(name, startDate, endDate)));
	}

	public TermView renameTerm(String termId, String name) {
		requireEditor();
		TermEntity term = lockedTerm(termId);
		if (terms.existsByNameIgnoreCaseAndIdNot(name, termId)) {
			throw nameTaken(name);
		}
		term.rename(name);
		return toView(term);
	}

	/**
	 * The copy is a new Draft with the same sections, so the source term is unaffected (FR1.1). A room that is out of
	 * service is left empty for the Scheduler to choose again, because placing a section there would be refused.
	 */
	public TermView copyTerm(String termId, String name) {
		CurrentUser user = requireEditor();
		TermEntity source = lockedTerm(termId);
		requireAllDepartments(user, termId);
		requireFreeName(name);
		TermEntity copy = terms.save(TermEntity.create(name, source.getStartDate(), source.getEndDate()));
		for (SectionEntity section : sections.findForTerm(termId)) {
			SectionEntity sectionCopy = section.copyTo(copy);
			if (sectionCopy.getRoom() != null && sectionCopy.getRoom().getStatus() != ResourceStatus.AVAILABLE) {
				sectionCopy.setRoom(null);
			}
			SectionEntity saved = sections.save(sectionCopy);
			conflicts.recheck(copy.getId(), ConflictService.Ref.section(saved.getId()));
		}
		return toView(copy);
	}

	/**
	 * A published term is live for students and instructors, so only a Draft can be deleted. Deleting removes every
	 * section, so the caller must be authorized for all of them.
	 */
	public void deleteTerm(String termId) {
		CurrentUser user = requireEditor();
		TermEntity term = lockedTerm(termId);
		if (term.isPublished()) {
			throw new ConflictingStateException("A published term cannot be deleted.");
		}
		requireAllDepartments(user, termId);
		terms.delete(term);
	}

	/**
	 * Open High conflicts need an explicit confirmation, but never block publishing (FR4.7). A term can span
	 * departments, so any authorized Scheduler may publish it.
	 */
	public TermView publish(String termId, boolean confirmed) {
		requireEditor();
		TermEntity term = lockedTerm(termId);
		if (term.isPublished()) {
			throw new ConflictingStateException("This term is already published.");
		}
		int openHigh = conflicts.countOpenHigh(termId);
		if (openHigh > 0 && !confirmed) {
			throw new ConflictingStateException("This term has " + openHigh + " open High-severity conflict"
					+ (openHigh == 1 ? "" : "s") + ". Confirm to publish anyway, or resolve them first.");
		}
		term.publish(LocalDateTime.now(clock));
		return toView(term);
	}

	// ---- Sections ----

	@Transactional(readOnly = true)
	public List<SectionView> listSections(String termId) {
		if (!currentUser.get().isScheduler()) {
			throw new AccessDeniedException("Only Schedulers can read a timetable under construction.");
		}
		term(termId);
		return toViews(sections.findForTerm(termId));
	}

	/** UC-02: saves the section whatever the conflict check finds. */
	public SectionView placeSection(String termId, SectionPlacement placement) {
		CurrentUser user = requireEditor();
		TermEntity term = lockedTerm(termId);
		SubjectEntity subject = subject(placement.subjectId());
		requireDepartment(user, subject);
		String number = checkedNumber(placement.sectionNumber());
		if (sections.existsByTerm_IdAndSubject_IdAndSectionNumber(termId, subject.getId(), number)) {
			throw new ConflictingStateException(subject.getCourseCode() + " section " + number
					+ " is already in this term.");
		}
		SectionEntity section = SectionEntity.create(term, subject, number);
		section.setRoom(usableRoom(placement.roomId(), null));
		section.setMeetings(checkedMeetings(placement.meetings(), term));
		section.setInstructorIds(checkedInstructors(placement.instructorIds()));
		section.setEquipment(checkedEquipment(placement.equipment()));
		SectionEntity saved = sections.save(section);
		conflicts.recheck(termId, ConflictService.Ref.section(saved.getId()));
		if (term.isPublished()) {
			notices.notifyAffected(saved.getId(), NoticeEvent.SECTION_CHANGED);
		}
		return toViews(List.of(saved)).get(0);
	}

	/** UC-03 and UC-04: replaces the placement. The subject and section number identify the section and stay fixed. */
	public SectionView updateSection(String termId, String sectionId, SectionPlacement placement) {
		CurrentUser user = requireEditor();
		TermEntity term = lockedTerm(termId);
		SectionEntity section = sectionIn(termId, sectionId);
		requireDepartment(user, section.getSubject());
		if ((placement.subjectId() != null && !placement.subjectId().equals(section.getSubject().getId()))
				|| (placement.sectionNumber() != null && !placement.sectionNumber().equals(section.getSectionNumber()))) {
			throw new BusinessRuleException(
					"A section's subject and number cannot be changed. Remove it and add a new section instead.");
		}
		applyChange(section, usableRoom(placement.roomId(), section.getRoom()),
				checkedInstructors(placement.instructorIds()), checkedMeetings(placement.meetings(), term),
				checkedEquipment(placement.equipment()));
		return toViews(List.of(section)).get(0);
	}

	/** UC-03 and UC-04, FR1.6: changes the room, the instructors (including co-instructors), or both. Null means keep. */
	public SectionView changeAssignment(String sectionId, String roomId, List<String> instructorIds) {
		CurrentUser user = requireEditor();
		String termId = sections.findTermId(sectionId).orElseThrow(() -> NotFoundException.of("Section", sectionId));
		lockedTerm(termId);
		SectionEntity section = sectionIn(termId, sectionId);
		requireDepartment(user, section.getSubject());
		RoomEntity room = roomId == null ? section.getRoom() : usableRoom(roomId, section.getRoom());
		Set<String> instructors = instructorIds == null ? section.getInstructorIds() : checkedInstructors(instructorIds);
		applyChange(section, room, instructors, new LinkedHashSet<>(section.getMeetings()),
				new LinkedHashSet<>(section.getEquipment()));
		return toViews(List.of(section)).get(0);
	}

	/** Removing a section from a Published term tells those affected, while the section still exists to look up. */
	public void removeSection(String termId, String sectionId) {
		CurrentUser user = requireEditor();
		TermEntity term = lockedTerm(termId);
		SectionEntity section = sectionIn(termId, sectionId);
		requireDepartment(user, section.getSubject());
		if (term.isPublished()) {
			notices.notifyAffected(sectionId, NoticeEvent.SECTION_CANCELLED);
		}
		sections.delete(section);
		conflicts.recheck(termId, ConflictService.Ref.section(sectionId));
	}

	/**
	 * One path for every edit: an edit that changes nothing is not re-checked and notifies nobody. An equipment change
	 * is re-checked, but notifies nobody because the room and times that people rely on stay the same. One edit sends
	 * one notice: SECTION_CHANGED also names the instructors, so it covers an edit that changes them too.
	 */
	private void applyChange(SectionEntity section, RoomEntity room, Set<String> instructors, Set<MeetingSlot> meetings,
			Set<EquipmentNeed> needs) {
		boolean scheduleChanged = !Objects.equals(section.getRoom(), room)
				|| !new HashSet<>(section.getMeetings()).equals(meetings);
		boolean instructorsChanged = !section.getInstructorIds().equals(instructors);
		boolean equipmentChanged = !new HashSet<>(section.getEquipment()).equals(needs);
		if (!scheduleChanged && !instructorsChanged && !equipmentChanged) {
			return;
		}
		Set<String> removedInstructors = new HashSet<>(section.getInstructorIds());
		removedInstructors.removeAll(instructors);
		section.setRoom(room);
		section.setMeetings(meetings);
		section.setInstructorIds(instructors);
		section.setEquipment(needs);
		if (scheduleChanged || instructorsChanged) {
			section.markModified();
		}
		TermEntity term = section.getTerm();
		conflicts.recheck(term.getId(), ConflictService.Ref.section(section.getId()));
		if (term.isPublished()) {
			if (scheduleChanged) {
				notices.notifyAffected(section.getId(), NoticeEvent.SECTION_CHANGED);
			} else if (instructorsChanged) {
				notices.notifyAffected(section.getId(), NoticeEvent.INSTRUCTOR_REASSIGNED);
			}
			if (!removedInstructors.isEmpty()) {
				notices.notifyRemovedInstructors(section.getId(), removedInstructors);
			}
		}
	}

	// ---- Checks ----

	/** A scheduler with no authorized departments may not change anything (TR3.4). */
	private CurrentUser requireEditor() {
		CurrentUser user = currentUser.get();
		if (!user.isScheduler() || user.departments().isEmpty()) {
			throw new AccessDeniedException("Your account is not authorized to change timetables.");
		}
		return user;
	}

	/** Imported subjects can have no department yet; any authorized scheduler may place those. */
	private static void requireDepartment(CurrentUser user, SubjectEntity subject) {
		requireDepartment(user, subject.getDepartment());
	}

	private static void requireDepartment(CurrentUser user, String department) {
		if (department != null && !user.canManage(department)) {
			throw new AccessDeniedException("Your account is not authorized for the " + department + " department.");
		}
	}

	private void requireAllDepartments(CurrentUser user, String termId) {
		sections.findDepartments(termId).forEach(department -> requireDepartment(user, department));
	}

	private void requireFreeName(String name) {
		if (terms.existsByNameIgnoreCase(name)) {
			throw nameTaken(name);
		}
	}

	private static ConflictingStateException nameTaken(String name) {
		return new ConflictingStateException("A term named '" + name + "' already exists.");
	}

	private TermEntity term(String termId) {
		return terms.findById(termId).orElseThrow(() -> NotFoundException.of("Term", termId));
	}

	private TermEntity lockedTerm(String termId) {
		return terms.lockById(termId).orElseThrow(() -> NotFoundException.of("Term", termId));
	}

	private SectionEntity sectionIn(String termId, String sectionId) {
		return sections.findById(sectionId)
				.filter(section -> section.getTerm().getId().equals(termId))
				.orElseThrow(() -> NotFoundException.of("Section", sectionId));
	}

	private static String checkedNumber(String sectionNumber) {
		if (sectionNumber == null) {
			return DEFAULT_SECTION_NUMBER;
		}
		if (sectionNumber.isBlank() || sectionNumber.length() > MAX_SECTION_NUMBER_LENGTH) {
			throw new BusinessRuleException(
					"A section number needs 1 to " + MAX_SECTION_NUMBER_LENGTH + " characters.");
		}
		return sectionNumber;
	}

	private SubjectEntity subject(String subjectId) {
		if (subjectId == null) {
			throw new BusinessRuleException("Choose a subject for this section.");
		}
		return subjects.findById(subjectId)
				.orElseThrow(() -> new BusinessRuleException("That subject is not in the catalog."));
	}

	/** Maintenance and Checked out rooms cannot be newly assigned, but a section already there can be edited. */
	private RoomEntity usableRoom(String roomId, RoomEntity current) {
		if (roomId == null) {
			throw new BusinessRuleException("Choose a room for this section.");
		}
		RoomEntity room = rooms.findById(roomId)
				.orElseThrow(() -> new BusinessRuleException("That room is not in the catalog."));
		if (room.getStatus() != ResourceStatus.AVAILABLE && !room.equals(current)) {
			throw new BusinessRuleException(room.getName() + " is not available for scheduling right now.");
		}
		return room;
	}

	private Set<String> checkedInstructors(List<String> instructorIds) {
		if (instructorIds == null || instructorIds.isEmpty()) {
			throw new BusinessRuleException("A section needs at least one instructor.");
		}
		Set<String> unique = new LinkedHashSet<>(instructorIds);
		if (users.findAllById(unique).size() != unique.size()) {
			throw new BusinessRuleException("One of the chosen instructors was not recognized.");
		}
		return unique;
	}

	private static Set<MeetingSlot> checkedMeetings(List<MeetingSlot> meetings, TermEntity term) {
		if (meetings == null || meetings.isEmpty()) {
			throw new BusinessRuleException("A section needs at least one meeting time.");
		}
		meetings.forEach(meeting -> checkMeeting(meeting, term));
		return new LinkedHashSet<>(meetings);
	}

	private static void checkMeeting(MeetingSlot meeting, TermEntity term) {
		if (meeting == null || meeting.pattern() == null) {
			throw new BusinessRuleException("Mark each meeting as weekly, one-time or exam.");
		}
		if (meeting.start() == null || meeting.end() == null || !meeting.start().isBefore(meeting.end())) {
			throw new BusinessRuleException("Start time must be before end time.");
		}
		if (meeting.pattern() == MeetingPattern.WEEKLY) {
			if (meeting.day() == null || meeting.date() != null) {
				throw new BusinessRuleException("A weekly meeting needs a day of the week and no date.");
			}
		} else if (meeting.date() == null || meeting.day() != null) {
			throw new BusinessRuleException("A one-time meeting or exam needs a date and no day of the week.");
		} else if (meeting.date().isBefore(term.getStartDate()) || meeting.date().isAfter(term.getEndDate())) {
			throw new BusinessRuleException("The date " + meeting.date() + " is outside this term.");
		}
	}

	private Set<EquipmentNeed> checkedEquipment(List<EquipmentNeed> needs) {
		if (needs == null) {
			return Set.of();
		}
		for (EquipmentNeed need : needs) {
			if (need == null || need.equipmentId() == null || !equipment.existsById(need.equipmentId())) {
				throw new BusinessRuleException("That equipment item is not in the catalog.");
			}
			if (need.units() < 1) {
				throw new BusinessRuleException("Requested equipment units must be at least 1.");
			}
		}
		if (needs.stream().map(EquipmentNeed::equipmentId).distinct().count() != needs.size()) {
			throw new BusinessRuleException("List each equipment item only once.");
		}
		return new LinkedHashSet<>(needs);
	}

	// ---- Views ----

	private static TermView toView(TermEntity term) {
		return new TermView(term.getId(), term.getName(), term.getStartDate(), term.getEndDate(), term.getStatus(),
				term.getPublishedAt());
	}

	private List<SectionView> toViews(List<SectionEntity> found) {
		Set<String> instructorIds = found.stream().flatMap(s -> s.getInstructorIds().stream())
				.collect(Collectors.toSet());
		Map<String, String> names = users.findAllById(instructorIds).stream()
				.collect(Collectors.toMap(AppUserEntity::getId, AppUserEntity::getDisplayName));
		return found.stream().map(section -> toView(section, names::get)).toList();
	}

	private static SectionView toView(SectionEntity section, Function<String, String> instructorName) {
		RoomEntity room = section.getRoom();
		List<SectionView.InstructorView> instructors = section.getInstructorIds().stream()
				.map(id -> new SectionView.InstructorView(id, instructorName.apply(id)))
				.sorted(Comparator.comparing(SectionView.InstructorView::name))
				.toList();
		return new SectionView(section.getId(), section.getTerm().getId(), section.getSubject().getId(),
				section.getSubject().getCourseCode(), section.getSectionNumber(), room == null ? null : room.getId(),
				room == null ? null : room.getName(), instructors, section.getMeetings(), section.getEquipment());
	}

}
