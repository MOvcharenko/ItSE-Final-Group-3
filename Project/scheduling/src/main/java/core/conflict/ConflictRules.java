package core.conflict;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import core.MeetingTime;
import domain.MeetingPattern;
import domain.ResourceType;

/**
 * Framework-free conflict detection engine (FR4, UC-06).
 * Evaluates the six conflict rules in milliseconds with plain Java objects.
 * Touching time windows do not overlap. PREFER availability blocks are advisory only.
 */
public class ConflictRules {

	public static final int DEFAULT_STUDENT_GROUP_THRESHOLD = 5;

	@FunctionalInterface
	public interface SharedStudentsLookup {
		int getSharedStudents(String section1Id, String section2Id);
	}

	private final int studentGroupThreshold;

	public ConflictRules() {
		this(DEFAULT_STUDENT_GROUP_THRESHOLD);
	}

	public ConflictRules(int studentGroupThreshold) {
		this.studentGroupThreshold = studentGroupThreshold;
	}

	public int getStudentGroupThreshold() {
		return studentGroupThreshold;
	}

	/**
	 * Runs the six conflict rules against the provided snapshots.
	 * If sharedStudentsLookup is null, the student-group clash rule is skipped (graceful degradation, UC-06 3a).
	 * If a section has null enrolledStudents, the capacity exceeded rule is skipped for it.
	 */
	public List<DetectedConflict> evaluate(
			List<ScheduledMeeting> sections,
			List<ScheduledBooking> bookings,
			List<InstructorAvailabilityBlock> availabilities,
			Map<String, EquipmentInfo> equipmentCatalog,
			SharedStudentsLookup sharedStudentsLookup
	) {
		if (sections == null) {
			sections = List.of();
		}
		if (bookings == null) {
			bookings = List.of();
		}
		if (availabilities == null) {
			availabilities = List.of();
		}
		if (equipmentCatalog == null) {
			equipmentCatalog = Map.of();
		}

		Map<String, DetectedConflict> conflictMap = new LinkedHashMap<>();

		// 1. Room double-booking (FR4.3a)
		checkRoomDoubleBookings(sections, bookings, conflictMap);

		// 2. Instructor overlap (FR4.3b)
		checkInstructorOverlaps(sections, conflictMap);

		// 3. Instructor unavailability (FR4.3c, FR4.6)
		checkInstructorUnavailability(sections, availabilities, conflictMap);

		// 4. Equipment double-booking (FR4.3d, OI-1)
		checkEquipmentDoubleBookings(sections, bookings, equipmentCatalog, conflictMap);

		// 5. Capacity exceeded (FR4.3e)
		checkCapacityExceeded(sections, conflictMap);

		// 6. Student-group clash (FR4.3f, OI-2)
		if (sharedStudentsLookup != null) {
			checkStudentGroupClash(sections, sharedStudentsLookup, conflictMap);
		}

		return new ArrayList<>(conflictMap.values());
	}

	private void checkRoomDoubleBookings(
			List<ScheduledMeeting> sections,
			List<ScheduledBooking> bookings,
			Map<String, DetectedConflict> conflicts
	) {
		// Section vs Section in same room
		for (int i = 0; i < sections.size(); i++) {
			ScheduledMeeting s1 = sections.get(i);
			if (s1.roomId() == null || s1.roomId().isBlank()) {
				continue;
			}
			for (int j = i + 1; j < sections.size(); j++) {
				ScheduledMeeting s2 = sections.get(j);
				if (s2.roomId() == null || !s1.roomId().equals(s2.roomId())) {
					continue;
				}

				for (MeetingTime m1 : s1.meetings()) {
					for (MeetingTime m2 : s2.meetings()) {
						if (m1.overlaps(m2)) {
							String sId1 = s1.sectionId();
							String sId2 = s2.sectionId();
							String firstSec = sId1.compareTo(sId2) < 0 ? sId1 : sId2;
							String secondSec = sId1.compareTo(sId2) < 0 ? sId2 : sId1;

							String fp = "ROOM_DOUBLE_BOOKING:" + s1.roomId() + ":" + firstSec + ":" + secondSec + ":" + m1.formatWindow();
							String explanation = "Room double-booking in " + s1.roomName() + ": " +
									s1.displayName() + " (" + m1.formatWindow() + ") and " +
									s2.displayName() + " (" + m2.formatWindow() + ") overlap.";

							conflicts.putIfAbsent(fp, DetectedConflict.roomDoubleBooking(
									explanation,
									fp,
									s1.roomId(),
									null,
									List.of(s1.sectionId(), s2.sectionId())
							));
						}
					}
				}
			}
		}

		// Section vs Approved Booking in same room
		for (ScheduledMeeting s : sections) {
			if (s.roomId() == null || s.roomId().isBlank()) {
				continue;
			}
			for (ScheduledBooking b : bookings) {
				if (!b.isApproved() || b.resourceType() != ResourceType.ROOM || !s.roomId().equals(b.resourceId())) {
					continue;
				}

				for (MeetingTime m : s.meetings()) {
					if (meetingOverlapsBooking(m, b)) {
						String fp = "ROOM_DOUBLE_BOOKING:" + s.roomId() + ":booking-" + b.bookingId() + ":section-" + s.sectionId() + ":" + m.formatWindow();
						String bWindow = b.startTime().toLocalDate() + " " + b.startTime().toLocalTime() + "–" + b.endTime().toLocalTime();
						String explanation = "Room double-booking in " + s.roomName() + ": " +
								s.displayName() + " (" + m.formatWindow() + ") and Booking '" +
								b.purpose() + "' (" + bWindow + ") overlap.";

						conflicts.putIfAbsent(fp, DetectedConflict.roomDoubleBooking(
								explanation,
								fp,
								s.roomId(),
								b.bookingId(),
								List.of(s.sectionId())
						));
					}
				}
			}
		}
	}

	private void checkInstructorOverlaps(
			List<ScheduledMeeting> sections,
			Map<String, DetectedConflict> conflicts
	) {
		for (int i = 0; i < sections.size(); i++) {
			ScheduledMeeting s1 = sections.get(i);
			for (int j = i + 1; j < sections.size(); j++) {
				ScheduledMeeting s2 = sections.get(j);
				if (s1.sectionId().equals(s2.sectionId())) {
					continue;
				}

				for (String instId : s1.instructorIds()) {
					if (s2.instructorIds().contains(instId)) {
						for (MeetingTime m1 : s1.meetings()) {
							for (MeetingTime m2 : s2.meetings()) {
								if (m1.overlaps(m2)) {
									long overlapMinutes = m1.overlapDuration(m2).toMinutes();
									String sId1 = s1.sectionId();
									String sId2 = s2.sectionId();
									String firstSec = sId1.compareTo(sId2) < 0 ? sId1 : sId2;
									String secondSec = sId1.compareTo(sId2) < 0 ? sId2 : sId1;

									String fp = "INSTRUCTOR_OVERLAP:" + instId + ":" + firstSec + ":" + secondSec + ":" + m1.formatWindow();
									String instName = s1.instructorName(instId);
									String explanation = "Instructor overlap for " + instName + ": " +
											s1.displayName() + " (" + m1.formatWindow() + ") and " +
											s2.displayName() + " (" + m2.formatWindow() + ") overlap by " +
											overlapMinutes + " minutes.";

									conflicts.putIfAbsent(fp, DetectedConflict.instructorOverlap(
											explanation,
											fp,
											instId,
											List.of(s1.sectionId(), s2.sectionId())
									));
								}
							}
						}
					}
				}
			}
		}
	}

	private void checkInstructorUnavailability(
			List<ScheduledMeeting> sections,
			List<InstructorAvailabilityBlock> availabilities,
			Map<String, DetectedConflict> conflicts
	) {
		for (ScheduledMeeting s : sections) {
			for (String instId : s.instructorIds()) {
				for (InstructorAvailabilityBlock block : availabilities) {
					if (!block.instructorId().equals(instId)) {
						continue;
					}
					// FR4.6: PREFER blocks are advisory only and never generate a conflict
					if (!block.isUnavailable()) {
						continue;
					}

					for (MeetingTime m : s.meetings()) {
						if (m.overlaps(block.dayOfWeek(), block.startTime(), block.endTime())) {
							String fp = "INSTRUCTOR_UNAVAILABLE:" + instId + ":" + s.sectionId() + ":" + m.formatWindow();
							String explanation = "Instructor " + block.instructorName() + " is unavailable during " +
									s.displayName() + " (" + m.formatWindow() + ").";

							conflicts.putIfAbsent(fp, DetectedConflict.instructorUnavailable(
									explanation,
									fp,
									instId,
									s.sectionId()
							));
						}
					}
				}
			}
		}
	}

	private void checkEquipmentDoubleBookings(
			List<ScheduledMeeting> sections,
			List<ScheduledBooking> bookings,
			Map<String, EquipmentInfo> equipmentCatalog,
			Map<String, DetectedConflict> conflicts
	) {
		for (EquipmentInfo equip : equipmentCatalog.values()) {
			int capacity = equip.unitCount();

			// For each section that needs this equipment
			for (ScheduledMeeting s : sections) {
				int sUnits = s.equipmentNeeds().getOrDefault(equip.id(), s.equipmentNeeds().getOrDefault(equip.name(), 0));
				if (sUnits <= 0) {
					continue;
				}

				for (MeetingTime m : s.meetings()) {
					int totalUnitsRequested = sUnits;
					List<String> affectedSections = new ArrayList<>();
					affectedSections.add(s.sectionId());
					String matchedBookingId = null;

					// Check other sections needing the same equipment overlapping with m
					for (ScheduledMeeting other : sections) {
						if (other.sectionId().equals(s.sectionId())) {
							continue;
						}
						int otherUnits = other.equipmentNeeds().getOrDefault(equip.id(), other.equipmentNeeds().getOrDefault(equip.name(), 0));
						if (otherUnits <= 0) {
							continue;
						}

						for (MeetingTime otherM : other.meetings()) {
							if (m.overlaps(otherM)) {
								totalUnitsRequested += otherUnits;
								if (!affectedSections.contains(other.sectionId())) {
									affectedSections.add(other.sectionId());
								}
							}
						}
					}

					// Check approved bookings for this equipment overlapping with m
					for (ScheduledBooking b : bookings) {
						if (!b.isApproved() || b.resourceType() != ResourceType.EQUIPMENT) {
							continue;
						}
						if (!b.resourceId().equals(equip.id()) && !b.resourceId().equals(equip.name())) {
							continue;
						}

						if (meetingOverlapsBooking(m, b)) {
							totalUnitsRequested += 1; // Each booking is 1 unit (or per booking item)
							matchedBookingId = b.bookingId();
						}
					}

					if (totalUnitsRequested > capacity) {
						String fp = "EQUIPMENT_DOUBLE_BOOKING:" + equip.id() + ":" + s.sectionId() + ":" + m.formatWindow();
						String explanation = "Equipment double-booking for " + equip.name() + ": " +
								totalUnitsRequested + " units requested during " + m.formatWindow() +
								", but only " + capacity + " available.";

						conflicts.putIfAbsent(fp, DetectedConflict.equipmentDoubleBooking(
								explanation,
								fp,
								equip.id(),
								matchedBookingId,
								affectedSections
						));
					}
				}
			}
		}
	}

	private void checkCapacityExceeded(
			List<ScheduledMeeting> sections,
			Map<String, DetectedConflict> conflicts
	) {
		for (ScheduledMeeting s : sections) {
			if (s.roomId() == null || s.roomCapacity() == null || s.enrolledStudents() == null) {
				continue;
			}

			if (s.enrolledStudents() > s.roomCapacity()) {
				String fp = "CAPACITY_EXCEEDED:" + s.sectionId() + ":" + s.roomId();
				String explanation = "Capacity exceeded for " + s.displayName() + " in " + s.roomName() +
						": enrollment of " + s.enrolledStudents() + " exceeds capacity of " + s.roomCapacity() + ".";

				conflicts.putIfAbsent(fp, DetectedConflict.capacityExceeded(
						explanation,
						fp,
						s.roomId(),
						s.sectionId()
				));
			}
		}
	}

	private void checkStudentGroupClash(
			List<ScheduledMeeting> sections,
			SharedStudentsLookup sharedLookup,
			Map<String, DetectedConflict> conflicts
	) {
		for (int i = 0; i < sections.size(); i++) {
			ScheduledMeeting s1 = sections.get(i);
			for (int j = i + 1; j < sections.size(); j++) {
				ScheduledMeeting s2 = sections.get(j);
				if (s1.sectionId().equals(s2.sectionId())) {
					continue;
				}

				for (MeetingTime m1 : s1.meetings()) {
					for (MeetingTime m2 : s2.meetings()) {
						if (m1.overlaps(m2)) {
							int shared = sharedLookup.getSharedStudents(s1.sectionId(), s2.sectionId());
							if (shared >= studentGroupThreshold) {
								String sId1 = s1.sectionId();
								String sId2 = s2.sectionId();
								String firstSec = sId1.compareTo(sId2) < 0 ? sId1 : sId2;
								String secondSec = sId1.compareTo(sId2) < 0 ? sId2 : sId1;

								String fp = "STUDENT_GROUP_CLASH:" + firstSec + ":" + secondSec + ":" + m1.formatWindow();
								String explanation = "Student-group clash between " + s1.displayName() +
										" and " + s2.displayName() + ": " + shared +
										" shared students have overlapping meetings (" + m1.formatWindow() + ").";

								conflicts.putIfAbsent(fp, DetectedConflict.studentGroupClash(
										explanation,
										fp,
										List.of(s1.sectionId(), s2.sectionId())
								));
							}
						}
					}
				}
			}
		}
	}

	private boolean meetingOverlapsBooking(MeetingTime m, ScheduledBooking b) {
		LocalDate bDate = b.startTime().toLocalDate();
		LocalTime bStart = b.startTime().toLocalTime();
		LocalTime bEnd = b.endTime().toLocalTime();

		// Time windows must overlap: touching windows do NOT overlap
		if (!m.start().isBefore(bEnd) || !bStart.isBefore(m.end())) {
			return false;
		}

		if (m.pattern() == MeetingPattern.WEEKLY) {
			return bDate.getDayOfWeek() == m.dayOfWeek();
		} else {
			return m.date() != null && m.date().equals(bDate);
		}
	}
}
