package service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * What the TIMETABLE already uses in a time window, so availability search (FR2.4, UC-09) does not offer a room
 * that a class meets in every Monday morning. OWNER: Dev A (timetable stream). Implement it as a Spring bean;
 * until one exists, BookingServiceImpl treats nothing as in use (see {@link #none()}).
 *
 * "In use" means: the room (or equipment) is attached to a placed section whose meeting overlaps the window, in a
 * term whose dates cover the window. A weekly meeting overlaps when its weekday matches a day in the window and its
 * clock times overlap (touching is not overlapping, like everywhere else).
 */
public interface TimetableOccupancy {

	/** Ids of rooms used by a section meeting in the window. */
	Set<String> roomIdsInUse(LocalDateTime windowStart, LocalDateTime windowEnd);

	/** Units of each equipment item (by id) that sections need in the window, from section_equipment.units. */
	Map<String, Integer> equipmentUnitsInUse(LocalDateTime windowStart, LocalDateTime windowEnd);

	static TimetableOccupancy none() {
		return new TimetableOccupancy() {
			@Override
			public Set<String> roomIdsInUse(LocalDateTime windowStart, LocalDateTime windowEnd) {
				return Set.of();
			}

			@Override
			public Map<String, Integer> equipmentUnitsInUse(LocalDateTime windowStart, LocalDateTime windowEnd) {
				return Map.of();
			}
		};
	}
}
