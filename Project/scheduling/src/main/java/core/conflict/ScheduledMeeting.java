package core.conflict;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import core.MeetingTime;

/**
 * Immutable snapshot of a scheduled section for conflict evaluation.
 * Framework-free: plain Java record.
 */
public record ScheduledMeeting(
		String sectionId,
		String courseCode,
		String sectionNumber,
		String roomId,
		String roomName,
		Integer roomCapacity,
		List<MeetingTime> meetings,
		Set<String> instructorIds,
		Map<String, String> instructorNames,
		Map<String, Integer> equipmentNeeds,
		Integer enrolledStudents
) {
	public ScheduledMeeting {
		Objects.requireNonNull(sectionId, "sectionId must not be null");
		courseCode = courseCode != null ? courseCode : "";
		sectionNumber = sectionNumber != null ? sectionNumber : "01";
		meetings = meetings == null ? List.of() : List.copyOf(meetings);
		instructorIds = instructorIds == null ? Set.of() : Set.copyOf(instructorIds);
		instructorNames = instructorNames == null ? Map.of() : Map.copyOf(instructorNames);
		equipmentNeeds = equipmentNeeds == null ? Map.of() : Map.copyOf(equipmentNeeds);
	}

	public String displayName() {
		return courseCode + (sectionNumber.isEmpty() ? "" : " sec " + sectionNumber);
	}

	public String instructorName(String id) {
		return instructorNames.getOrDefault(id, id);
	}
}
