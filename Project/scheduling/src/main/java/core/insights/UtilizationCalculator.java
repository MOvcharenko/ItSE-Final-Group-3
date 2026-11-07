package core.insights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure calculation logic for instructor and room utilization (FR5.1, FR5.2, OI-3).
 * No Spring or database dependencies.
 */
public class UtilizationCalculator {

	/**
	 * Configurable thresholds for insights (OI-3).
	 *
	 * @param instructorUnderusedThreshold below this ratio of scheduled / declared (Open+Prefer), instructor is under-used (default 0.50)
	 * @param instructorConfirmationThreshold from this ratio upwards, instructor receives positive confirmation (default 0.80)
	 * @param roomTeachingWeekHours total teaching hours in a standard week, Mon-Fri 08:00-18:00 (default 50.0)
	 * @param roomUnderusedOccupancyThreshold below this ratio of scheduled hours / teaching week hours, room is underused (default 0.20, or 0.05 for 40h week in Td1LoaderTest)
	 * @param roomOversizedFillThreshold when room occupancy >= underused threshold but average fill < this threshold, room is oversized (default 0.40)
	 */
	public record Thresholds(
			double instructorUnderusedThreshold,
			double instructorConfirmationThreshold,
			double roomTeachingWeekHours,
			double roomUnderusedOccupancyThreshold,
			double roomOversizedFillThreshold
	) {
		public static Thresholds defaults() {
			return new Thresholds(0.50, 0.80, 50.0, 0.20, 0.40);
		}
	}

	public record InstructorStat(
			String instructorId,
			String instructorName,
			double scheduledMinutes,
			double declaredMinutes,
			double ratio,
			Verdict verdict
	) {
		public enum Verdict {
			UNDERUSED,
			CONFIRMATION,
			NONE
		}
	}

	public record RoomStat(
			String roomId,
			String roomName,
			int capacity,
			double scheduledMinutes,
			double occupancyRatio,
			double averageFillRatio,
			Verdict verdict
	) {
		public enum Verdict {
			UNDERUSED,
			OVERSIZED,
			WELL_UTILIZED
		}
	}

	public record MeetingSnapshot(
			String sectionId,
			String roomId,
			String roomName,
			int roomCapacity,
			int enrolledCount,
			int durationMinutes,
			Set<String> instructorIds
	) {
	}

	public record DeclaredBlockSnapshot(
			String instructorId,
			int durationMinutes,
			String status // OPEN, PREFER, UNAVAILABLE
	) {
	}

	/**
	 * Calculates instructor utilization stats (FR5.1).
	 * Denominator is declared OPEN + PREFER hours (UNAVAILABLE is excluded).
	 * Instructors who declared nothing have no bar and generate no suggestion (TC-FR5.1).
	 */
	public List<InstructorStat> calculateInstructors(
			Map<String, String> instructorNames,
			Collection<MeetingSnapshot> meetings,
			Collection<DeclaredBlockSnapshot> declaredBlocks,
			Thresholds thresholds
	) {
		Map<String, Double> scheduledMinutesByInst = new HashMap<>();
		for (MeetingSnapshot m : meetings) {
			if (m.instructorIds() != null) {
				for (String instId : m.instructorIds()) {
					scheduledMinutesByInst.merge(instId, (double) m.durationMinutes(), Double::sum);
				}
			}
		}

		Map<String, Double> declaredMinutesByInst = new HashMap<>();
		for (DeclaredBlockSnapshot b : declaredBlocks) {
			if ("OPEN".equalsIgnoreCase(b.status()) || "PREFER".equalsIgnoreCase(b.status())) {
				declaredMinutesByInst.merge(b.instructorId(), (double) b.durationMinutes(), Double::sum);
			}
		}

		List<InstructorStat> results = new ArrayList<>();
		// Iterate only instructors who declared at least one OPEN/PREFER block
		for (Map.Entry<String, Double> entry : declaredMinutesByInst.entrySet()) {
			String instId = entry.getKey();
			double declared = entry.getValue();
			if (declared <= 0) {
				continue;
			}
			double scheduled = scheduledMinutesByInst.getOrDefault(instId, 0.0);
			double ratio = scheduled / declared;

			InstructorStat.Verdict verdict;
			if (ratio < thresholds.instructorUnderusedThreshold()) {
				verdict = InstructorStat.Verdict.UNDERUSED;
			} else if (ratio >= thresholds.instructorConfirmationThreshold()) {
				verdict = InstructorStat.Verdict.CONFIRMATION;
			} else {
				verdict = InstructorStat.Verdict.NONE;
			}

			String name = instructorNames.getOrDefault(instId, instId);
			results.add(new InstructorStat(instId, name, scheduled, declared, ratio, verdict));
		}

		return results;
	}

	/**
	 * Calculates room utilization along occupancy and fill (FR5.2).
	 */
	public List<RoomStat> calculateRooms(
			Map<String, RoomInfo> rooms,
			Collection<MeetingSnapshot> meetings,
			Thresholds thresholds
	) {
		Map<String, Double> scheduledMinutesByRoom = new HashMap<>();
		for (MeetingSnapshot m : meetings) {
			if (m.roomId() != null && !m.roomId().isBlank()) {
				scheduledMinutesByRoom.merge(m.roomId(), (double) m.durationMinutes(), Double::sum);
			}
		}

		// Fill is average enrollment / capacity across sections in the room (counted once per section)
		Map<String, List<Double>> fillsByRoom = new HashMap<>();
		Set<String> seenSections = new HashSet<>();
		for (MeetingSnapshot m : meetings) {
			if (m.roomId() != null && !m.roomId().isBlank() && seenSections.add(m.sectionId())) {
				if (m.roomCapacity() > 0) {
					double fill = (double) m.enrolledCount() / m.roomCapacity();
					fillsByRoom.computeIfAbsent(m.roomId(), k -> new ArrayList<>()).add(fill);
				}
			}
		}

		double totalWeekMinutes = thresholds.roomTeachingWeekHours() * 60.0;
		List<RoomStat> results = new ArrayList<>();

		for (RoomInfo room : rooms.values()) {
			double scheduledMinutes = scheduledMinutesByRoom.getOrDefault(room.id(), 0.0);
			double occupancy = totalWeekMinutes > 0 ? (scheduledMinutes / totalWeekMinutes) : 0.0;

			List<Double> sectionFills = fillsByRoom.getOrDefault(room.id(), List.of());
			double avgFill = sectionFills.isEmpty() ? 0.0 : sectionFills.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

			RoomStat.Verdict verdict;
			if (occupancy < thresholds.roomUnderusedOccupancyThreshold()) {
				verdict = RoomStat.Verdict.UNDERUSED;
			} else if (avgFill < thresholds.roomOversizedFillThreshold()) {
				verdict = RoomStat.Verdict.OVERSIZED;
			} else {
				verdict = RoomStat.Verdict.WELL_UTILIZED;
			}

			results.add(new RoomStat(room.id(), room.name(), room.capacity(), scheduledMinutes, occupancy, avgFill, verdict));
		}

		return results;
	}

	public record RoomInfo(String id, String name, int capacity) {
	}
}
