package service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import core.insights.UtilizationCalculator;
import domain.ConflictType;
import domain.InsightAxis;
import domain.InsightMeasure;
import integration.SisClient;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.ConflictEntity;
import persistence.ConflictRepository;
import persistence.InsightSuggestionEntity;
import persistence.InsightSuggestionRepository;
import persistence.InstructorAvailabilityEntity;
import persistence.InstructorAvailabilityRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SectionEntity;
import persistence.SectionMeeting;
import persistence.SectionRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import service.view.InsightView;

@Service
public class DefaultInsightsService implements InsightsService {

	private final InsightSuggestionRepository suggestions;
	private final ConflictRepository conflicts;
	private final TermRepository terms;
	private final SectionRepository sections;
	private final RoomRepository rooms;
	private final AppUserRepository users;
	private final InstructorAvailabilityRepository availabilities;
	private final SisClient sis;
	private final Caller caller;
	private final Clock clock;
	private final UtilizationCalculator calculator;
	private final UtilizationCalculator.Thresholds thresholds;

	public DefaultInsightsService(
			InsightSuggestionRepository suggestions,
			ConflictRepository conflicts,
			TermRepository terms,
			SectionRepository sections,
			RoomRepository rooms,
			AppUserRepository users,
			InstructorAvailabilityRepository availabilities,
			SisClient sis,
			Caller caller,
			Clock clock,
			@Value("${scheduling.insights.instructor-underused:0.50}") double instructorUnderused,
			@Value("${scheduling.insights.instructor-confirm:0.80}") double instructorConfirm,
			@Value("${scheduling.insights.room-week-hours:50.0}") double roomWeekHours,
			@Value("${scheduling.insights.room-underused-occ:0.20}") double roomUnderusedOcc,
			@Value("${scheduling.insights.room-oversized-fill:0.40}") double roomOversizedFill
	) {
		this.suggestions = suggestions;
		this.conflicts = conflicts;
		this.terms = terms;
		this.sections = sections;
		this.rooms = rooms;
		this.users = users;
		this.availabilities = availabilities;
		this.sis = sis;
		this.caller = caller;
		this.clock = clock;
		this.calculator = new UtilizationCalculator();
		this.thresholds = new UtilizationCalculator.Thresholds(
				instructorUnderused, instructorConfirm, roomWeekHours, roomUnderusedOcc, roomOversizedFill
		);
	}

	@Override
	@Transactional
	public InsightView getInsights(String termId) {
		caller.requireScheduler();
		TermEntity term = terms.findById(termId)
				.orElseThrow(() -> NotFoundException.of("Term", termId));

		List<SectionEntity> sectionList = sections.findForTerm(termId);
		List<InstructorAvailabilityEntity> availList = availabilities.findByTerm_Id(termId);
		List<RoomEntity> roomList = rooms.findAll();
		List<AppUserEntity> userList = users.findAll();
		Map<String, String> instructorNames = userList.stream()
				.collect(Collectors.toMap(AppUserEntity::getId, AppUserEntity::getDisplayName, (a, b) -> a));

		// Build meeting snapshots for calculator
		List<UtilizationCalculator.MeetingSnapshot> meetingSnapshots = new ArrayList<>();
		for (SectionEntity s : sectionList) {
			int enrolled = 0;
			try {
				if (sis.isAvailable()) {
					enrolled = sis.getEnrollmentCount(s.getId());
					if (enrolled == 0 && s.getSubject() != null) {
						enrolled = sis.getEnrollmentCount(s.getSubject().getCourseCode(), s.getSectionNumber());
					}
				}
			} catch (Exception ignored) {
			}

			RoomEntity r = s.getRoom();
			String roomId = r != null ? r.getId() : null;
			String roomName = r != null ? r.getName() : null;
			int cap = r != null ? r.getCapacity() : 0;
			Set<String> instIds = s.getInstructorIds();

			for (var m : s.getMeetings()) {
				int durationMinutes = (int) Duration.between(m.start(), m.end()).toMinutes();
				meetingSnapshots.add(new UtilizationCalculator.MeetingSnapshot(
						s.getId(), roomId, roomName, cap, enrolled, durationMinutes, instIds
				));
			}
		}

		// Build declared availability snapshots
		List<UtilizationCalculator.DeclaredBlockSnapshot> declaredSnapshots = new ArrayList<>();
		for (InstructorAvailabilityEntity a : availList) {
			int durationMinutes = (int) Duration.between(a.getStartTime(), a.getEndTime()).toMinutes();
			declaredSnapshots.add(new UtilizationCalculator.DeclaredBlockSnapshot(
					a.getInstructor().getId(), durationMinutes, a.getStatus().name()
			));
		}

		// Calculate instructor stats
		List<UtilizationCalculator.InstructorStat> instStats = calculator.calculateInstructors(
				instructorNames, meetingSnapshots, declaredSnapshots, thresholds
		);

		// Calculate room stats
		Map<String, UtilizationCalculator.RoomInfo> roomMap = roomList.stream()
				.collect(Collectors.toMap(RoomEntity::getId, r -> new UtilizationCalculator.RoomInfo(r.getId(), r.getName(), r.getCapacity())));
		List<UtilizationCalculator.RoomStat> roomStats = calculator.calculateRooms(roomMap, meetingSnapshots, thresholds);

		// Open conflicts for this term
		List<ConflictEntity> openConflicts = conflicts.findForTermDetailed(termId).stream()
				.filter(ConflictEntity::isOpen)
				.toList();
		Map<String, ConflictEntity> conflictsByInstructor = new HashMap<>();
		Map<String, ConflictEntity> conflictsByRoom = new HashMap<>();
		for (ConflictEntity c : openConflicts) {
			if (c.getInstructor() != null) {
				conflictsByInstructor.putIfAbsent(c.getInstructor().getId(), c);
			}
			if (c.getRoom() != null) {
				conflictsByRoom.putIfAbsent(c.getRoom().getId(), c);
			}
		}

		LocalDateTime now = LocalDateTime.now(clock);

		// Synchronize suggestions in DB
		List<InsightSuggestionEntity> existingSuggestions = suggestions.findByTerm_Id(termId);

		// 1. Process instructor suggestions
		for (var stat : instStats) {
			if (stat.verdict() == UtilizationCalculator.InstructorStat.Verdict.UNDERUSED) {
				ConflictEntity relConflict = conflictsByInstructor.get(stat.instructorId());
				Optional<InsightSuggestionEntity> existing = existingSuggestions.stream()
						.filter(s -> s.getAxis() == InsightAxis.INSTRUCTOR_UTILIZATION && s.getInstructor() != null && s.getInstructor().getId().equals(stat.instructorId()))
						.findFirst();

				String msg = String.format("Instructor %s is scheduled for %.1f of %.1f declared hours (%.0f%% utilization).",
						stat.instructorName(), stat.scheduledMinutes() / 60.0, stat.declaredMinutes() / 60.0, stat.ratio() * 100);

				if (existing.isPresent()) {
					InsightSuggestionEntity ent = existing.get();
					ent.setMessage(msg);
					ent.setRelatedConflict(relConflict);
				} else {
					AppUserEntity inst = users.findById(stat.instructorId()).orElse(null);
					if (inst != null) {
						InsightSuggestionEntity ent = InsightSuggestionEntity.createInstructorSuggestion(term, inst, relConflict, msg, now);
						suggestions.save(ent);
					}
				}
			}
		}

		// 2. Process room suggestions
		for (var stat : roomStats) {
			if (stat.verdict() == UtilizationCalculator.RoomStat.Verdict.UNDERUSED) {
				ConflictEntity relConflict = conflictsByRoom.get(stat.roomId());
				Optional<InsightSuggestionEntity> existing = existingSuggestions.stream()
						.filter(s -> s.getAxis() == InsightAxis.ROOM_CAPACITY && s.getMeasure() == InsightMeasure.OCCUPANCY && s.getRoom() != null && s.getRoom().getId().equals(stat.roomId()))
						.findFirst();

				String msg = String.format("Room %s has low occupancy (%.1f scheduled hours, %.0f%% of week). Room underused.",
						stat.roomName(), stat.scheduledMinutes() / 60.0, stat.occupancyRatio() * 100);

				if (existing.isPresent()) {
					InsightSuggestionEntity ent = existing.get();
					ent.setMessage(msg);
					ent.setRelatedConflict(relConflict);
				} else {
					RoomEntity rm = rooms.findById(stat.roomId()).orElse(null);
					if (rm != null) {
						InsightSuggestionEntity ent = InsightSuggestionEntity.createRoomSuggestion(term, InsightMeasure.OCCUPANCY, rm, relConflict, msg, now);
						suggestions.save(ent);
					}
				}
			} else if (stat.verdict() == UtilizationCalculator.RoomStat.Verdict.OVERSIZED) {
				ConflictEntity relConflict = conflictsByRoom.get(stat.roomId());
				Optional<InsightSuggestionEntity> existing = existingSuggestions.stream()
						.filter(s -> s.getAxis() == InsightAxis.ROOM_CAPACITY && s.getMeasure() == InsightMeasure.FILL && s.getRoom() != null && s.getRoom().getId().equals(stat.roomId()))
						.findFirst();

				String msg = String.format("Room %s runs at %.0f%% average fill relative to capacity %d. Oversized for its sections.",
						stat.roomName(), stat.averageFillRatio() * 100, stat.capacity());

				if (existing.isPresent()) {
					InsightSuggestionEntity ent = existing.get();
					ent.setMessage(msg);
					ent.setRelatedConflict(relConflict);
				} else {
					RoomEntity rm = rooms.findById(stat.roomId()).orElse(null);
					if (rm != null) {
						InsightSuggestionEntity ent = InsightSuggestionEntity.createRoomSuggestion(term, InsightMeasure.FILL, rm, relConflict, msg, now);
						suggestions.save(ent);
					}
				}
			}
		}

		// Re-fetch all suggestions for view
		List<InsightSuggestionEntity> currentSuggestions = suggestions.findByTerm_Id(termId);

		// Build View
		List<InsightView.InstructorInsight> instructorViews = instStats.stream()
				.map(s -> new InsightView.InstructorInsight(
						s.instructorId(),
						s.instructorName(),
						round(s.scheduledMinutes() / 60.0),
						round(s.declaredMinutes() / 60.0),
						round(s.ratio() * 100.0),
						s.verdict().name()
				))
				.toList();

		List<InsightView.RoomInsight> roomViews = roomStats.stream()
				.map(s -> new InsightView.RoomInsight(
						s.roomId(),
						s.roomName(),
						s.capacity(),
						round(s.scheduledMinutes() / 60.0),
						round(s.occupancyRatio() * 100.0),
						round(s.averageFillRatio() * 100.0),
						s.verdict().name()
				))
				.toList();

		List<InsightView.SuggestionCard> cardViews = currentSuggestions.stream()
				.map(s -> {
					String resId = s.getAxis() == InsightAxis.INSTRUCTOR_UTILIZATION
							? (s.getInstructor() != null ? s.getInstructor().getId() : null)
							: (s.getRoom() != null ? s.getRoom().getId() : null);
					String resName = s.getAxis() == InsightAxis.INSTRUCTOR_UTILIZATION
							? (s.getInstructor() != null ? s.getInstructor().getDisplayName() : null)
							: (s.getRoom() != null ? s.getRoom().getName() : null);
					boolean alreadyFlagged = s.getRelatedConflict() != null && s.getRelatedConflict().isOpen();
					String conflictId = alreadyFlagged ? s.getRelatedConflict().getId() : null;

					return new InsightView.SuggestionCard(
							s.getId(),
							s.getAxis(),
							s.getMeasure(),
							resId,
							resName,
							s.getMessage(),
							s.isDismissed(),
							alreadyFlagged,
							conflictId,
							s.getCreatedAt()
					);
				})
				.toList();

		return new InsightView(termId, instructorViews, roomViews, cardViews);
	}

	@Override
	@Transactional
	public void dismissSuggestion(String suggestionId) {
		caller.requireScheduler();
		InsightSuggestionEntity entity = suggestions.findById(suggestionId)
				.orElseThrow(() -> NotFoundException.of("InsightSuggestion", suggestionId));
		entity.dismiss();
	}

	private static double round(double val) {
		return Math.round(val * 10.0) / 10.0;
	}
}
