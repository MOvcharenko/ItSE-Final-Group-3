package api.dto;

import java.util.List;

import domain.MeetingSlot;

/**
 * Payload to resolve a conflict by reassigning a section's room, instructors, or meeting slots (UC-05).
 */
public record ReassignRequest(
		String sectionId,
		String roomId,
		List<String> instructorIds,
		List<MeetingSlot> meetings
) {
}
