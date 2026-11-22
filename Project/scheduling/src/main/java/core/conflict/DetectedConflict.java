package core.conflict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import domain.ConflictType;
import domain.Severity;

/**
 * Immutable detected conflict produced by ConflictRules.
 * Contains stable fingerprint and plain-language explanation (NFR4.1).
 */
public record DetectedConflict(
		ConflictType type,
		Severity severity,
		String explanation,
		String fingerprint,
		String roomId,
		String instructorId,
		String equipmentId,
		String bookingId,
		List<String> sectionIds,
		List<ConflictMeeting> meetings
) {
	public DetectedConflict {
		Objects.requireNonNull(type, "ConflictType must not be null");
		Objects.requireNonNull(severity, "Severity must not be null");
		Objects.requireNonNull(explanation, "Explanation must not be null");
		Objects.requireNonNull(fingerprint, "Fingerprint must not be null");
		sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
		meetings = meetings == null ? List.of() : List.copyOf(meetings);
	}

	/** Without meetings: the conflict is about the sections as a whole (capacity exceeded). */
	public DetectedConflict(ConflictType type, Severity severity, String explanation, String fingerprint, String roomId,
			String instructorId, String equipmentId, String bookingId, List<String> sectionIds) {
		this(type, severity, explanation, fingerprint, roomId, instructorId, equipmentId, bookingId, sectionIds, List.of());
	}

	/** The same conflict, naming the meetings that clash. */
	public DetectedConflict withMeetings(List<ConflictMeeting> clashing) {
		return new DetectedConflict(type, severity, explanation, fingerprint, roomId, instructorId, equipmentId,
				bookingId, sectionIds, clashing);
	}

	/** The same conflict found again at another pair of meetings: their meetings together, each once. */
	public DetectedConflict mergedWith(DetectedConflict again) {
		List<ConflictMeeting> all = new ArrayList<>(meetings);
		again.meetings().stream().filter(meeting -> !all.contains(meeting)).forEach(all::add);
		return withMeetings(all);
	}

	public static DetectedConflict roomDoubleBooking(
			String explanation,
			String fingerprint,
			String roomId,
			String bookingId,
			List<String> sectionIds
	) {
		return new DetectedConflict(
				ConflictType.ROOM_DOUBLE_BOOKING,
				Severity.HIGH,
				explanation,
				fingerprint,
				roomId,
				null,
				null,
				bookingId,
				sectionIds
		);
	}

	public static DetectedConflict instructorOverlap(
			String explanation,
			String fingerprint,
			String instructorId,
			List<String> sectionIds
	) {
		return new DetectedConflict(
				ConflictType.INSTRUCTOR_OVERLAP,
				Severity.HIGH,
				explanation,
				fingerprint,
				null,
				instructorId,
				null,
				null,
				sectionIds
		);
	}

	public static DetectedConflict instructorUnavailable(
			String explanation,
			String fingerprint,
			String instructorId,
			String sectionId
	) {
		return new DetectedConflict(
				ConflictType.INSTRUCTOR_UNAVAILABLE,
				Severity.HIGH,
				explanation,
				fingerprint,
				null,
				instructorId,
				null,
				null,
				List.of(sectionId)
		);
	}

	public static DetectedConflict equipmentDoubleBooking(
			String explanation,
			String fingerprint,
			String equipmentId,
			String bookingId,
			List<String> sectionIds
	) {
		return new DetectedConflict(
				ConflictType.EQUIPMENT_DOUBLE_BOOKING,
				Severity.MEDIUM,
				explanation,
				fingerprint,
				null,
				null,
				equipmentId,
				bookingId,
				sectionIds
		);
	}

	public static DetectedConflict capacityExceeded(
			String explanation,
			String fingerprint,
			String roomId,
			String sectionId
	) {
		return new DetectedConflict(
				ConflictType.CAPACITY_EXCEEDED,
				Severity.MEDIUM,
				explanation,
				fingerprint,
				roomId,
				null,
				null,
				null,
				List.of(sectionId)
		);
	}

	public static DetectedConflict studentGroupClash(
			String explanation,
			String fingerprint,
			List<String> sectionIds
	) {
		return new DetectedConflict(
				ConflictType.STUDENT_GROUP_CLASH,
				Severity.LOW,
				explanation,
				fingerprint,
				null,
				null,
				null,
				null,
				sectionIds
		);
	}
}
