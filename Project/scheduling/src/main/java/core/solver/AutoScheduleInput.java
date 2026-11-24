package core.solver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import core.conflict.ConflictRules;
import core.conflict.EquipmentInfo;
import core.conflict.InstructorAvailabilityBlock;
import core.conflict.ScheduledBooking;
import core.conflict.ScheduledMeeting;
import domain.EquipmentNeed;
import domain.RoomType;

/**
 * Immutable input snapshot for {@link TimetableSatSolver} (framework-free).
 */
public record AutoScheduleInput(
		List<SectionRequest> requests,
		List<RoomOption> rooms,
		List<InstructorOption> instructors,
		List<ScheduledMeeting> fixedSections,
		List<ScheduledBooking> approvedBookings,
		List<InstructorAvailabilityBlock> availabilities,
		Map<String, EquipmentInfo> equipmentCatalog,
		ConflictRules.SharedStudentsLookup sharedStudentsLookup,
		List<PreviousSolution> excludedSolutions,
		SolverOptions options
) {

	public AutoScheduleInput {
		requests = requests == null ? List.of() : List.copyOf(requests);
		rooms = rooms == null ? List.of() : List.copyOf(rooms);
		instructors = instructors == null ? List.of() : List.copyOf(instructors);
		fixedSections = fixedSections == null ? List.of() : List.copyOf(fixedSections);
		approvedBookings = approvedBookings == null ? List.of() : List.copyOf(approvedBookings);
		availabilities = availabilities == null ? List.of() : List.copyOf(availabilities);
		equipmentCatalog = equipmentCatalog == null ? Map.of() : Map.copyOf(equipmentCatalog);
		excludedSolutions = excludedSolutions == null ? List.of() : List.copyOf(excludedSolutions);
		options = options == null ? SolverOptions.defaults() : options;
	}

	public record SectionRequest(
			String subjectId,
			String courseCode,
			String subjectTitle,
			String department,
			String sectionNumber,
			int creditHours,
			RoomType requiredRoomType,
			Set<String> requiredFixedEquipment,
			List<EquipmentNeed> portableEquipment,
			int enrolledStudents,
			List<String> candidateInstructorIds,
			String patternStyle
	) {
		public SectionRequest {
			Objects.requireNonNull(subjectId, "subjectId must not be null");
			courseCode = courseCode == null ? "" : courseCode;
			subjectTitle = subjectTitle == null ? "" : subjectTitle;
			sectionNumber = sectionNumber == null || sectionNumber.isBlank() ? "01" : sectionNumber;
			requiredFixedEquipment = requiredFixedEquipment == null ? Set.of() : Set.copyOf(requiredFixedEquipment);
			portableEquipment = portableEquipment == null ? List.of() : List.copyOf(portableEquipment);
			candidateInstructorIds = candidateInstructorIds == null ? List.of() : List.copyOf(candidateInstructorIds);
		}
	}

	public record RoomOption(
			String roomId,
			String name,
			String building,
			int capacity,
			RoomType roomType,
			Set<String> fixedEquipment
	) {
		public RoomOption {
			Objects.requireNonNull(roomId, "roomId must not be null");
			fixedEquipment = fixedEquipment == null ? Set.of() : Set.copyOf(fixedEquipment);
		}
	}

	public record InstructorOption(
			String instructorId,
			String name,
			String department
	) {
		public InstructorOption {
			Objects.requireNonNull(instructorId, "instructorId must not be null");
			name = name == null ? instructorId : name;
		}
	}

	public record PlacementKey(
			String subjectId,
			String sectionNumber,
			String roomId,
			String instructorId,
			String patternId
	) {
	}

	public record PreviousSolution(List<PlacementKey> placements) {
		public PreviousSolution {
			placements = placements == null ? List.of() : List.copyOf(placements);
		}
	}

	public record SolverOptions(
			boolean keepExistingFixed,
			boolean avoidSplitDays,
			boolean preferPrimeHours,
			boolean respectPreferences,
			boolean optimizeRoomFit,
			long timeoutMillis
	) {
		public static SolverOptions defaults() {
			return new SolverOptions(true, true, true, true, true, 2000L);
		}
	}
}
