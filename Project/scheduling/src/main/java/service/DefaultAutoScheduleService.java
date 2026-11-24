package service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import api.dto.AutoScheduleApplyRequest;
import api.dto.AutoScheduleRequest;
import core.MeetingTime;
import core.conflict.ConflictRules;
import core.conflict.EquipmentInfo;
import core.conflict.InstructorAvailabilityBlock;
import core.conflict.ScheduledBooking;
import core.conflict.ScheduledMeeting;
import core.solver.AutoScheduleInput;
import core.solver.AutoScheduleInput.InstructorOption;
import core.solver.AutoScheduleInput.PlacementKey;
import core.solver.AutoScheduleInput.PreviousSolution;
import core.solver.AutoScheduleInput.RoomOption;
import core.solver.AutoScheduleInput.SectionRequest;
import core.solver.AutoScheduleInput.SolverOptions;
import core.solver.AutoScheduleResult;
import core.solver.TimetableSatSolver;
import domain.BookingStatus;
import domain.EquipmentNeed;
import domain.ResourceStatus;
import domain.ResourceType;
import integration.SisClient;
import integration.SisUnavailableException;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.InstructorAvailabilityEntity;
import persistence.InstructorAvailabilityRepository;
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
import service.view.AutoScheduleView;
import service.view.SectionView;

/**
 * Coordinates the SAT/MaxSAT timetable solver against live catalog, availability, booking,
 * and SIS snapshots, and commits approved schedules inside a locked Draft term transaction.
 */
@Service
public class DefaultAutoScheduleService implements AutoScheduleService {

	private final TermRepository terms;
	private final SectionRepository sections;
	private final SubjectRepository subjects;
	private final RoomRepository rooms;
	private final EquipmentRepository equipment;
	private final AppUserRepository users;
	private final InstructorAvailabilityRepository availabilities;
	private final BookingRepository bookings;
	private final SisClient sis;
	private final TimetableService timetableService;
	private final CurrentUserProvider currentUser;
	private final TimetableSatSolver solver = new TimetableSatSolver();

	public DefaultAutoScheduleService(
			TermRepository terms,
			SectionRepository sections,
			SubjectRepository subjects,
			RoomRepository rooms,
			EquipmentRepository equipment,
			AppUserRepository users,
			InstructorAvailabilityRepository availabilities,
			BookingRepository bookings,
			SisClient sis,
			TimetableService timetableService,
			CurrentUserProvider currentUser
	) {
		this.terms = terms;
		this.sections = sections;
		this.subjects = subjects;
		this.rooms = rooms;
		this.equipment = equipment;
		this.users = users;
		this.availabilities = availabilities;
		this.bookings = bookings;
		this.sis = sis;
		this.timetableService = timetableService;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public AutoScheduleView preview(String termId, AutoScheduleRequest request) {
		CurrentUser user = requireSchedulerEditor();
		TermEntity term = terms.findById(termId).orElseThrow(() -> NotFoundException.of("Term", termId));
		if (term.isPublished()) {
			throw new ConflictingStateException("Only Draft terms can be auto-scheduled.");
		}

		AutoScheduleRequest req = request != null ? request : new AutoScheduleRequest(null, null, null, null, null, null, null);
		boolean keepExisting = req.keepExistingFixed() == null || req.keepExistingFixed();

		List<SectionEntity> existingSections = sections.findForTerm(termId);
		List<SubjectEntity> allSubjects = subjects.findAllByOrderByCourseCodeAsc();
		Map<String, SubjectEntity> subjectById = allSubjects.stream()
				.collect(Collectors.toMap(SubjectEntity::getId, s -> s, (a, b) -> a, LinkedHashMap::new));

		List<RoomOption> roomOptions = rooms.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE).stream()
				.map(r -> new RoomOption(r.getId(), r.getName(), r.getBuilding(), r.getCapacity(),
						r.getRoomType(), r.getFixedEquipment()))
				.toList();

		List<AppUserEntity> allUsers = users.findAll();
		Map<String, AppUserEntity> userById = allUsers.stream()
				.collect(Collectors.toMap(AppUserEntity::getId, u -> u, (a, b) -> a, LinkedHashMap::new));
		List<InstructorOption> instructorOptions = allUsers.stream()
				.map(u -> new InstructorOption(u.getId(), u.getDisplayName(), u.getDepartment()))
				.toList();

		Map<String, EquipmentEntity> equipByIdOrType = new HashMap<>();
		Map<String, EquipmentInfo> equipCatalog = new LinkedHashMap<>();
		for (EquipmentEntity eq : equipment.findAllByOrderByNameAsc()) {
			equipCatalog.put(eq.getId(), new EquipmentInfo(eq.getId(), eq.getName(), eq.getItemType(), eq.getUnitCount()));
			equipByIdOrType.put(eq.getId(), eq);
			equipByIdOrType.putIfAbsent(eq.getName().toLowerCase(), eq);
			equipByIdOrType.putIfAbsent(eq.getItemType().toLowerCase(), eq);
		}

		List<SectionRequest> sectionRequests = resolveSectionRequests(
				req, keepExisting, user, existingSections, allSubjects, subjectById, allUsers, userById, equipByIdOrType);

		List<ScheduledMeeting> fixedSnapshots = keepExisting
				? toFixedSnapshots(existingSections, userById, equipCatalog)
				: List.of();

		LocalDateTime termStart = term.getStartDate().atStartOfDay();
		LocalDateTime termEnd = term.getEndDate().plusDays(1).atStartOfDay();
		List<ScheduledBooking> approvedBookings = bookings.findAll().stream()
				.filter(b -> b.getStatus() == BookingStatus.APPROVED
						&& b.getStartTime().isBefore(termEnd)
						&& b.getEndTime().isAfter(termStart))
				.map(b -> new ScheduledBooking(
						b.getId(),
						b.getResourceType(),
						b.resourceId(),
						b.resourceName(),
						b.getStartTime(),
						b.getEndTime(),
						b.getPurpose(),
						b.getStatus()))
				.toList();

		List<InstructorAvailabilityBlock> availBlocks = new ArrayList<>();
		for (InstructorAvailabilityEntity a : availabilities.findByTerm_Id(termId)) {
			availBlocks.add(new InstructorAvailabilityBlock(
					a.getInstructor().getId(),
					a.getInstructor().getDisplayName(),
					a.getDayOfWeek(),
					a.getStartTime(),
					a.getEndTime(),
					a.getStatus()));
		}

		ConflictRules.SharedStudentsLookup sharedLookup = null;
		try {
			if (sis.isAvailable()) {
				sharedLookup = (id1, id2) -> {
					try {
						return sis.getSharedStudents(id1, id2);
					} catch (Exception ex) {
						return 0;
					}
				};
			}
		} catch (Exception ignored) {
		}

		List<PreviousSolution> excluded = toPreviousSolutions(req.excludedSolutions());
		SolverOptions solverOptions = new SolverOptions(
				keepExisting,
				req.avoidSplitDays() == null || req.avoidSplitDays(),
				req.preferPrimeHours() == null || req.preferPrimeHours(),
				req.respectPreferences() == null || req.respectPreferences(),
				req.optimizeRoomFit() == null || req.optimizeRoomFit(),
				2000L);

		AutoScheduleInput input = new AutoScheduleInput(
				sectionRequests, roomOptions, instructorOptions, fixedSnapshots,
				approvedBookings, availBlocks, equipCatalog, sharedLookup, excluded, solverOptions);

		AutoScheduleResult result = solver.solve(input);
		return AutoScheduleView.fromResult(termId, result);
	}

	@Override
	@Transactional
	public List<SectionView> apply(String termId, AutoScheduleApplyRequest request) {
		CurrentUser user = requireSchedulerEditor();
		TermEntity term = terms.lockById(termId).orElseThrow(() -> NotFoundException.of("Term", termId));
		if (term.isPublished()) {
			throw new ConflictingStateException("Only Draft terms can be auto-scheduled.");
		}

		if (Boolean.TRUE.equals(request.replaceExisting())) {
			for (String dept : sections.findDepartments(termId)) {
				if (dept != null && !user.canManage(dept)) {
					throw new AccessDeniedException("Your account is not authorized for the " + dept + " department.");
				}
			}
			List<SectionEntity> existing = sections.findForTerm(termId);
			if (!existing.isEmpty()) {
				sections.deleteAll(existing);
				sections.flush();
			}
		}

		for (AutoScheduleApplyRequest.PlacementToApply item : request.placements()) {
			timetableService.placeSection(termId, new SectionPlacement(
					item.subjectId(),
					item.sectionNumber(),
					item.roomId(),
					item.instructorIds(),
					item.meetings(),
					item.equipment()));
		}

		return timetableService.listSections(termId);
	}

	// ---- Helpers ----

	private List<SectionRequest> resolveSectionRequests(
			AutoScheduleRequest req,
			boolean keepExisting,
			CurrentUser user,
			List<SectionEntity> existingSections,
			List<SubjectEntity> allSubjects,
			Map<String, SubjectEntity> subjectById,
			List<AppUserEntity> allUsers,
			Map<String, AppUserEntity> userById,
			Map<String, EquipmentEntity> equipByIdOrType
	) {
		List<SectionRequest> requests = new ArrayList<>();
		if (req.items() == null || req.items().isEmpty()) {
			Set<String> scheduledSubjectIds = existingSections.stream()
					.map(s -> s.getSubject().getId())
					.collect(Collectors.toSet());
			for (SubjectEntity subj : allSubjects) {
				if (subj.getDepartment() != null && !user.canManage(subj.getDepartment())) {
					continue;
				}
				if (keepExisting && scheduledSubjectIds.contains(subj.getId())) {
					continue;
				}
				String nextNum = keepExisting ? nextSectionNumber(existingSections, subj.getId()) : "01";
				requests.add(toSectionRequest(subj, nextNum, null, "AUTO", allUsers, equipByIdOrType));
			}
			return requests;
		}

		for (AutoScheduleRequest.SubjectItem item : req.items()) {
			if (item == null || item.subjectId() == null) {
				throw new BusinessRuleException("Choose a valid subject to auto-schedule.");
			}
			SubjectEntity subj = subjectById.get(item.subjectId());
			if (subj == null) {
				throw new BusinessRuleException("Subject '" + item.subjectId() + "' is not in the catalog.");
			}
			if (subj.getDepartment() != null && !user.canManage(subj.getDepartment())) {
				throw new AccessDeniedException("Your account is not authorized for the " + subj.getDepartment() + " department.");
			}
			List<String> candidateIds;
			if (item.instructorIds() != null && !item.instructorIds().isEmpty()) {
				for (String id : item.instructorIds()) {
					if (!userById.containsKey(id)) {
						throw new BusinessRuleException("Instructor '" + id + "' was not recognized.");
					}
				}
				candidateIds = item.instructorIds();
			} else {
				candidateIds = null;
			}
			String sectionNum = item.sectionNumber() != null && !item.sectionNumber().isBlank()
					? item.sectionNumber()
					: (keepExisting ? nextSectionNumber(existingSections, subj.getId()) : "01");
			requests.add(toSectionRequest(subj, sectionNum, candidateIds, item.patternStyle(), allUsers, equipByIdOrType));
		}
		return requests;
	}

	private SectionRequest toSectionRequest(
			SubjectEntity subj,
			String sectionNumber,
			List<String> explicitInstructors,
			String patternStyle,
			List<AppUserEntity> allUsers,
			Map<String, EquipmentEntity> equipByIdOrType
	) {
		int enrolled = 0;
		try {
			if (sis.isAvailable()) {
				enrolled = sis.getEnrollmentCount(subj.getCourseCode(), sectionNumber);
			}
		} catch (SisUnavailableException ignored) {
			enrolled = 0;
		}

		Set<String> fixedEquip = new LinkedHashSet<>();
		List<EquipmentNeed> portableNeeds = new ArrayList<>();
		for (String itemType : subj.getTypicalEquipment()) {
			EquipmentEntity portable = equipByIdOrType.get(itemType.toLowerCase());
			if (portable != null) {
				portableNeeds.add(new EquipmentNeed(portable.getId(), 1));
			} else {
				fixedEquip.add(itemType);
			}
		}

		List<String> candidates = explicitInstructors != null
				? explicitInstructors
				: defaultInstructorsFor(subj, allUsers);

		return new SectionRequest(
				subj.getId(),
				subj.getCourseCode(),
				subj.getTitle(),
				subj.getDepartment(),
				sectionNumber,
				subj.getCreditHours(),
				subj.getDefaultRoomType(),
				fixedEquip,
				portableNeeds,
				enrolled,
				candidates,
				patternStyle);
	}

	private static List<String> defaultInstructorsFor(SubjectEntity subj, List<AppUserEntity> allUsers) {
		if (subj.getDepartment() != null && !subj.getDepartment().isBlank()) {
			List<String> deptMatches = allUsers.stream()
					.filter(u -> subj.getDepartment().equalsIgnoreCase(u.getDepartment()))
					.map(AppUserEntity::getId)
					.toList();
			if (!deptMatches.isEmpty()) {
				return deptMatches;
			}
		}
		List<String> faculty = allUsers.stream()
				.filter(u -> u.getDepartment() != null && !u.getDepartment().isBlank())
				.map(AppUserEntity::getId)
				.toList();
		if (!faculty.isEmpty()) {
			return faculty;
		}
		return allUsers.stream().map(AppUserEntity::getId).toList();
	}

	private static String nextSectionNumber(List<SectionEntity> existing, String subjectId) {
		int max = 0;
		for (SectionEntity s : existing) {
			if (s.getSubject().getId().equals(subjectId)) {
				try {
					max = Math.max(max, Integer.parseInt(s.getSectionNumber().trim()));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return String.format("%02d", max + 1);
	}

	private List<ScheduledMeeting> toFixedSnapshots(
			List<SectionEntity> existingSections,
			Map<String, AppUserEntity> userById,
			Map<String, EquipmentInfo> equipCatalog
	) {
		List<ScheduledMeeting> list = new ArrayList<>(existingSections.size());
		for (SectionEntity s : existingSections) {
			List<MeetingTime> mt = s.getMeetings().stream().map(MeetingTime::fromSlot).toList();
			Map<String, String> instNames = new HashMap<>();
			for (String id : s.getInstructorIds()) {
				AppUserEntity u = userById.get(id);
				if (u != null) {
					instNames.put(id, u.getDisplayName());
				}
			}
			Map<String, Integer> eqNeeds = new HashMap<>();
			for (EquipmentNeed need : s.getEquipment()) {
				eqNeeds.put(need.equipmentId(), need.units());
				EquipmentInfo info = equipCatalog.get(need.equipmentId());
				if (info != null) {
					eqNeeds.put(info.name(), need.units());
				}
			}
			Integer enrolled = null;
			try {
				if (sis.isAvailable()) {
					enrolled = sis.getEnrollmentCount(s.getId());
					if (enrolled == 0 && s.getSubject() != null) {
						enrolled = sis.getEnrollmentCount(s.getSubject().getCourseCode(), s.getSectionNumber());
					}
				}
			} catch (Exception ignored) {
			}
			list.add(new ScheduledMeeting(
					s.getId(),
					s.getSubject().getCourseCode(),
					s.getSectionNumber(),
					s.getRoom() != null ? s.getRoom().getId() : null,
					s.getRoom() != null ? s.getRoom().getName() : null,
					s.getRoom() != null ? s.getRoom().getCapacity() : null,
					mt,
					s.getInstructorIds(),
					instNames,
					eqNeeds,
					enrolled));
		}
		return list;
	}

	private static List<PreviousSolution> toPreviousSolutions(List<List<AutoScheduleRequest.PlacementChoice>> raw) {
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<PreviousSolution> list = new ArrayList<>(raw.size());
		for (List<AutoScheduleRequest.PlacementChoice> group : raw) {
			if (group == null || group.isEmpty()) {
				continue;
			}
			List<PlacementKey> keys = group.stream()
					.map(c -> new PlacementKey(c.subjectId(),
							c.sectionNumber() == null || c.sectionNumber().isBlank() ? "01" : c.sectionNumber(),
							c.roomId(), c.instructorId(), c.patternId()))
					.toList();
			list.add(new PreviousSolution(keys));
		}
		return list;
	}

	private CurrentUser requireSchedulerEditor() {
		CurrentUser user = currentUser.get();
		if (!user.isScheduler() || user.departments().isEmpty()) {
			throw new AccessDeniedException("Your account is not authorized to change timetables.");
		}
		return user;
	}
}
