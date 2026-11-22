package service.view;

import java.time.LocalDateTime;
import java.util.List;

import core.conflict.ConflictMeeting;
import domain.ConflictType;
import domain.ResolutionState;
import domain.Severity;

public record ConflictView(
		String id,
		String termId,
		ConflictType type,
		Severity severity,
		String explanation,
		ResolutionState resolutionState,
		String fingerprint,
		LocalDateTime detectedAt,
		LocalDateTime resolvedAt,
		String roomId,
		String roomName,
		String instructorId,
		String instructorName,
		String equipmentId,
		String equipmentName,
		String bookingId,
		List<String> sectionIds,
		/** The meetings that clash, each with its section; empty = the sections as a whole (capacity exceeded). */
		List<ConflictMeeting> meetings
) {
}
