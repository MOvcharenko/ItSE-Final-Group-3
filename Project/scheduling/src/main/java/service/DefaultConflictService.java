package service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import api.dto.ReassignRequest;
import core.MeetingTime;
import core.conflict.ConflictRules;
import core.conflict.DetectedConflict;
import core.conflict.EquipmentInfo;
import core.conflict.InstructorAvailabilityBlock;
import core.conflict.ScheduledBooking;
import core.conflict.ScheduledMeeting;
import domain.BookingStatus;
import domain.ConflictType;
import domain.NoticeEvent;
import domain.ResolutionState;
import domain.ResourceType;
import domain.Severity;
import integration.SisClient;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.AuditLogEntryEntity;
import persistence.AuditLogEntryRepository;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.ConflictEntity;
import persistence.ConflictRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.InstructorAvailabilityEntity;
import persistence.InstructorAvailabilityRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SectionEntity;
import persistence.SectionEquipment;
import persistence.SectionMeeting;
import persistence.SectionRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import service.view.ConflictView;
import service.view.DismissalView;

/**
 * Real implementation of ConflictService (FR4, UC-05, UC-06, owner: Dev B).
 * Runs conflict detection inside Spring transactions, persists detected conflicts,
 * manages dismissals with an immutable audit trail, and gates term publishing.
 */
@Service
public class DefaultConflictService implements ConflictService {

	private final ConflictRepository conflicts;
	private final AuditLogEntryRepository auditLogs;
	private final TermRepository terms;
	private final SectionRepository sections;
	private final RoomRepository rooms;
	private final EquipmentRepository equipment;
	private final BookingRepository bookings;
	private final AppUserRepository users;
	private final InstructorAvailabilityRepository availabilities;
	private final SisClient sis;
	private final Clock clock;
	private final Caller caller;
	private final UserProfiles userProfiles;
	private final NoticeService notices;
	private final ConflictRules rules;

	public DefaultConflictService(
			ConflictRepository conflicts,
			AuditLogEntryRepository auditLogs,
			TermRepository terms,
			SectionRepository sections,
			RoomRepository rooms,
			EquipmentRepository equipment,
			BookingRepository bookings,
			AppUserRepository users,
			InstructorAvailabilityRepository availabilities,
			SisClient sis,
			Clock clock,
			Caller caller,
			UserProfiles userProfiles,
			NoticeService notices
	) {
		this.conflicts = conflicts;
		this.auditLogs = auditLogs;
		this.terms = terms;
		this.sections = sections;
		this.rooms = rooms;
		this.equipment = equipment;
		this.bookings = bookings;
		this.users = users;
		this.availabilities = availabilities;
		this.sis = sis;
		this.clock = clock;
		this.caller = caller;
		this.userProfiles = userProfiles;
		this.notices = notices;
		this.rules = new ConflictRules();
	}

	@Override
	@Transactional(readOnly = true)
	public int countOpenHigh(String termId) {
		if (termId == null || termId.isBlank()) {
			return 0;
		}
		return conflicts.countOpenHigh(termId);
	}

	@Override
	@Transactional
	public void recheck(String termId, Ref changed) {
		if (termId == null || termId.isBlank()) {
			return;
		}
		TermEntity term = terms.findById(termId).orElse(null);
		if (term == null) {
			return;
		}

		// 1. Sections for term
		List<SectionEntity> sectionEntities = sections.findForTerm(termId);

		// 2. Approved bookings
		List<BookingEntity> approvedBookings = bookings.findAll().stream()
				.filter(b -> b.getStatus() == BookingStatus.APPROVED)
				.toList();

		// 3. Instructor availability blocks
		List<InstructorAvailabilityEntity> availEntities = availabilities.findByTerm_Id(termId);

		// 4. Equipment catalog
		Map<String, EquipmentInfo> equipmentCatalog = equipment.findAll().stream()
				.collect(Collectors.toMap(
						EquipmentEntity::getId,
						e -> new EquipmentInfo(e.getId(), e.getName(), e.getItemType(), e.getUnitCount()),
						(a, b) -> a
				));

		// 5. Build section snapshots
		List<ScheduledMeeting> sectionSnapshots = new ArrayList<>();
		for (SectionEntity s : sectionEntities) {
			List<MeetingTime> meetingTimes = s.getMeetings().stream()
					.map(MeetingTime::fromSlot)
					.toList();

			Map<String, String> instNames = new HashMap<>();
			for (String instId : s.getInstructorIds()) {
				users.findById(instId).ifPresent(u -> instNames.put(instId, u.getDisplayName()));
			}

			Map<String, Integer> equipNeeds = new HashMap<>();
			for (domain.EquipmentNeed en : s.getEquipment()) {
				equipNeeds.put(en.equipmentId(), en.units());
				equipment.findById(en.equipmentId())
						.ifPresent(e -> equipNeeds.put(e.getName(), en.units()));
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
				// Graceful degradation (UC-06 3a)
				enrolled = null;
			}

			sectionSnapshots.add(new ScheduledMeeting(
					s.getId(),
					s.getSubject() != null ? s.getSubject().getCourseCode() : "",
					s.getSectionNumber(),
					s.getRoom() != null ? s.getRoom().getId() : null,
					s.getRoom() != null ? s.getRoom().getName() : null,
					s.getRoom() != null ? s.getRoom().getCapacity() : null,
					meetingTimes,
					s.getInstructorIds(),
					instNames,
					equipNeeds,
					enrolled
			));
		}

		// 6. Build booking snapshots
		List<ScheduledBooking> bookingSnapshots = new ArrayList<>();
		for (BookingEntity b : approvedBookings) {
			String resId = b.getResourceType() == ResourceType.ROOM
					? (b.getRoom() != null ? b.getRoom().getId() : "")
					: (b.getEquipment() != null ? b.getEquipment().getId() : "");
			bookingSnapshots.add(new ScheduledBooking(
					b.getId(),
					b.getResourceType(),
					resId,
					b.resourceName(),
					b.getStartTime(),
					b.getEndTime(),
					b.getPurpose(),
					b.getStatus()
			));
		}

		// 7. Build availability snapshots
		List<InstructorAvailabilityBlock> availBlocks = new ArrayList<>();
		for (InstructorAvailabilityEntity a : availEntities) {
			availBlocks.add(new InstructorAvailabilityBlock(
					a.getInstructor().getId(),
					a.getInstructor().getDisplayName(),
					a.getDayOfWeek(),
					a.getStartTime(),
					a.getEndTime(),
					a.getStatus()
			));
		}

		// 8. SIS shared students lookup
		ConflictRules.SharedStudentsLookup sharedLookup = null;
		try {
			if (sis.isAvailable()) {
				sharedLookup = (s1, s2) -> {
					try {
						return sis.getSharedStudents(s1, s2);
					} catch (Exception e) {
						return 0;
					}
				};
			}
		} catch (Exception ignored) {
		}

		// 9. Evaluate rules
		List<DetectedConflict> detectedList = rules.evaluate(
				sectionSnapshots, bookingSnapshots, availBlocks, equipmentCatalog, sharedLookup
		);

		// 10. Reconcile with database
		LocalDateTime now = LocalDateTime.now(clock);
		List<ConflictEntity> existingList = conflicts.findByTerm_Id(termId);
		Map<String, ConflictEntity> existingByFp = existingList.stream()
				.collect(Collectors.toMap(ConflictEntity::getFingerprint, c -> c, (a, b) -> a));

		Set<String> activeFingerprints = new HashSet<>();

		for (DetectedConflict d : detectedList) {
			activeFingerprints.add(d.fingerprint());
			ConflictEntity existing = existingByFp.get(d.fingerprint());
			if (existing != null) {
				if (existing.isDismissed()) {
					// NFR3.3: Dismissed conflict stays dismissed
					continue;
				}
				if (existing.getResolutionState() == ResolutionState.RESOLVED) {
					existing.reopen(now);
					existing.setExplanation(d.explanation());
				}
			} else {
				ConflictEntity newConflict = ConflictEntity.create(
						term, d.type(), d.severity(), d.explanation(), d.fingerprint(), now
				);
				if (d.roomId() != null) {
					rooms.findById(d.roomId()).ifPresent(newConflict::setRoom);
				}
				if (d.instructorId() != null) {
					users.findById(d.instructorId()).ifPresent(newConflict::setInstructor);
				}
				if (d.equipmentId() != null) {
					equipment.findById(d.equipmentId()).ifPresent(newConflict::setEquipment);
				}
				if (d.bookingId() != null) {
					bookings.findById(d.bookingId()).ifPresent(newConflict::setBooking);
				}
				if (d.sectionIds() != null) {
					newConflict.setSectionIds(d.sectionIds());
				}
				conflicts.save(newConflict);
			}
		}

		// Clear / resolve vanished conflicts
		for (ConflictEntity existing : existingList) {
			if (existing.isOpen() && !activeFingerprints.contains(existing.getFingerprint())) {
				existing.resolve(now);
			}
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<ConflictView> listConflicts(String termId, ConflictType typeOrNull, Severity severityOrNull,
			ResolutionState stateOrNull) {
		caller.requireScheduler();
		List<ConflictEntity> list = conflicts.findForTermDetailed(termId);
		return list.stream()
				.filter(c -> typeOrNull == null || c.getConflictType() == typeOrNull)
				.filter(c -> severityOrNull == null || c.getSeverity() == severityOrNull)
				.filter(c -> stateOrNull == null || c.getResolutionState() == stateOrNull)
				.map(this::toView)
				.toList();
	}

	@Override
	@Transactional
	public List<ConflictView> rescan(String termId) {
		caller.requireScheduler();
		recheck(termId, Ref.term(termId));
		return listConflicts(termId, null, null, null);
	}

	@Override
	@Transactional
	public ConflictView reassign(String conflictId, ReassignRequest request) {
		caller.requireScheduler();
		ConflictEntity conflict = conflicts.findById(conflictId)
				.orElseThrow(() -> new NotFoundException("Conflict not found: " + conflictId));

		String targetSectionId = request.sectionId();
		if (targetSectionId == null || targetSectionId.isBlank()) {
			if (!conflict.getSectionIds().isEmpty()) {
				targetSectionId = conflict.getSectionIds().iterator().next();
			} else {
				throw new BusinessRuleException("No section specified to reassign.");
			}
		}
		final String sectionId = targetSectionId;
		SectionEntity section = sections.findById(sectionId)
				.orElseThrow(() -> new NotFoundException("Section not found: " + sectionId));

		if (request.roomId() != null) {
			RoomEntity newRoom = rooms.findById(request.roomId())
					.orElseThrow(() -> new NotFoundException("Room not found: " + request.roomId()));
			section.setRoom(newRoom);
		}
		if (request.instructorIds() != null && !request.instructorIds().isEmpty()) {
			section.setInstructorIds(new LinkedHashSet<>(request.instructorIds()));
		}
		if (request.meetings() != null && !request.meetings().isEmpty()) {
			section.setMeetings(request.meetings());
		}
		section.markModified();

		recheck(conflict.getTerm().getId(), Ref.section(section.getId()));

		if (conflict.getTerm().isPublished()) {
			notices.notifyAffected(section.getId(), NoticeEvent.SECTION_CHANGED);
		}

		return toView(conflict);
	}

	@Override
	@Transactional
	public ConflictView dismiss(String conflictId, String reason) {
		caller.requireScheduler();
		if (reason == null || reason.isBlank()) {
			throw new BusinessRuleException("A reason is required to dismiss a conflict.");
		}
		ConflictEntity conflict = conflicts.findById(conflictId)
				.orElseThrow(() -> new NotFoundException("Conflict not found: " + conflictId));

		conflict.dismiss();

		AppUserEntity user = userProfiles.ensure(caller);
		AuditLogEntryEntity audit = AuditLogEntryEntity.record(
				conflict.getId(),
				conflict.getConflictType(),
				conflict.getExplanation(),
				user,
				LocalDateTime.now(clock),
				reason.trim()
		);
		auditLogs.save(audit);

		return toView(conflict);
	}

	@Override
	@Transactional(readOnly = true)
	public List<DismissalView> listDismissals(String termId) {
		caller.requireScheduler();
		if (termId == null || !terms.existsById(termId)) {
			throw NotFoundException.of("Term", termId);
		}
		return auditLogs.findForTerm(termId).stream()
				.map(a -> new DismissalView(a.getId(), a.getConflictId(), a.getConflictType(), a.getConflictExplanation(),
						a.getUser().getId(), a.getUser().getDisplayName(), a.getOccurredAt(), a.getReason()))
				.toList();
	}

	private ConflictView toView(ConflictEntity c) {
		return new ConflictView(
				c.getId(),
				c.getTerm().getId(),
				c.getConflictType(),
				c.getSeverity(),
				c.getExplanation(),
				c.getResolutionState(),
				c.getFingerprint(),
				c.getDetectedAt(),
				c.getResolvedAt(),
				c.getRoom() != null ? c.getRoom().getId() : null,
				c.getRoom() != null ? c.getRoom().getName() : null,
				c.getInstructor() != null ? c.getInstructor().getId() : null,
				c.getInstructor() != null ? c.getInstructor().getDisplayName() : null,
				c.getEquipment() != null ? c.getEquipment().getId() : null,
				c.getEquipment() != null ? c.getEquipment().getName() : null,
				c.getBooking() != null ? c.getBooking().getId() : null,
				new ArrayList<>(c.getSectionIds())
		);
	}
}
