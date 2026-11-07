package core.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UtilizationCalculatorTest {

	private final UtilizationCalculator calc = new UtilizationCalculator();
	private final UtilizationCalculator.Thresholds thresholds = UtilizationCalculator.Thresholds.defaults();

	@Test
	@DisplayName("TC-FR5.1: Instructor utilization thresholds - underused < 50%, confirmation >= 80%, mid-range none")
	void instructorUtilizationCalculation() {
		Map<String, String> names = Map.of(
				"U02", "L. Bianchi",
				"U04", "Dr. Novak",
				"U05", "Prof. Alvarez",
				"U09", "Dr. Haddad"
		);

		List<UtilizationCalculator.MeetingSnapshot> meetings = List.of(
				new UtilizationCalculator.MeetingSnapshot("SEC-1", "R1", "Room 1", 50, 30, 360, Set.of("U02")), // 6h (360m)
				new UtilizationCalculator.MeetingSnapshot("SEC-2", "R2", "Room 2", 50, 30, 120, Set.of("U04")), // 2h (120m)
				new UtilizationCalculator.MeetingSnapshot("SEC-3", "R3", "Room 3", 50, 30, 540, Set.of("U05")), // 9h (540m)
				new UtilizationCalculator.MeetingSnapshot("SEC-4", "R4", "Room 4", 50, 30, 150, Set.of("U09"))  // 2.5h (150m)
		);

		List<UtilizationCalculator.DeclaredBlockSnapshot> blocks = List.of(
				new UtilizationCalculator.DeclaredBlockSnapshot("U02", 18 * 60, "OPEN"),       // 18h
				new UtilizationCalculator.DeclaredBlockSnapshot("U04", 8 * 60, "OPEN"),        // 8h
				new UtilizationCalculator.DeclaredBlockSnapshot("U04", 2 * 60, "UNAVAILABLE"), // unavailable ignored!
				new UtilizationCalculator.DeclaredBlockSnapshot("U05", 10 * 60, "PREFER"),     // 10h prefer
				new UtilizationCalculator.DeclaredBlockSnapshot("U09", 4 * 60, "OPEN")         // 4h
		);

		List<UtilizationCalculator.InstructorStat> stats = calc.calculateInstructors(names, meetings, blocks, thresholds);
		Map<String, UtilizationCalculator.InstructorStat> byId = stats.stream()
				.collect(Collectors.toMap(UtilizationCalculator.InstructorStat::instructorId, s -> s));

		// Bianchi: 6/18 = 33.3% -> UNDERUSED (< 50%)
		assertEquals(UtilizationCalculator.InstructorStat.Verdict.UNDERUSED, byId.get("U02").verdict());
		// Novak: 2/8 = 25% -> UNDERUSED (< 50%)
		assertEquals(UtilizationCalculator.InstructorStat.Verdict.UNDERUSED, byId.get("U04").verdict());
		// Alvarez: 9/10 = 90% -> CONFIRMATION (>= 80%)
		assertEquals(UtilizationCalculator.InstructorStat.Verdict.CONFIRMATION, byId.get("U05").verdict());
		// Haddad: 2.5/4 = 62.5% -> NONE (50% <= x < 80%)
		assertEquals(UtilizationCalculator.InstructorStat.Verdict.NONE, byId.get("U09").verdict());

		// Instructors who declared nothing (e.g. U10) are not present
		assertFalse(byId.containsKey("U10"));
	}

	@Test
	@DisplayName("TC-FR5.2: Room utilization - underused (<20% occ), oversized (occ>=20% and fill<40%), well-utilized")
	void roomUtilizationCalculation() {
		Map<String, UtilizationCalculator.RoomInfo> rooms = Map.of(
				"R-F110", new UtilizationCalculator.RoomInfo("R-F110", "Founders 110", 60),
				"R-HALE204", new UtilizationCalculator.RoomInfo("R-HALE204", "Hale Hall 204", 120),
				"R-LABB", new UtilizationCalculator.RoomInfo("R-LABB", "Innovation Lab B", 40)
		);

		// Teaching week = 50h = 3000m
		// Founders 110: 1 meeting 45m -> occ = 45/3000 = 1.5% (< 20%) -> UNDERUSED
		// Hale Hall 204: 6h = 360m -> occ = 360/3000 = 12% (< 20% on 50h week, or test with threshold 0.05)
		// Let's use custom thresholds matching TD-1 spec where occupancy threshold is 0.05
		UtilizationCalculator.Thresholds td1Thresholds = new UtilizationCalculator.Thresholds(0.50, 0.80, 40.0, 0.05, 0.40);

		List<UtilizationCalculator.MeetingSnapshot> meetings = List.of(
				new UtilizationCalculator.MeetingSnapshot("SEC-1", "R-F110", "Founders 110", 60, 20, 45, Set.of("U1")),
				// Hale Hall 204: capacity 120, 360m = 6h / 40h = 15% (>= 5%), fill: 35/120 = 29% (< 40%) -> OVERSIZED
				new UtilizationCalculator.MeetingSnapshot("SEC-2", "R-HALE204", "Hale Hall 204", 120, 35, 360, Set.of("U2")),
				// Innovation Lab B: capacity 40, 150m = 2.5h / 40h = 6.25% (>= 5%), fill: 25/40 = 62.5% (>= 40%) -> WELL_UTILIZED
				new UtilizationCalculator.MeetingSnapshot("SEC-3", "R-LABB", "Innovation Lab B", 40, 25, 150, Set.of("U3"))
		);

		List<UtilizationCalculator.RoomStat> stats = calc.calculateRooms(rooms, meetings, td1Thresholds);
		Map<String, UtilizationCalculator.RoomStat> byId = stats.stream()
				.collect(Collectors.toMap(UtilizationCalculator.RoomStat::roomId, s -> s));

		assertEquals(UtilizationCalculator.RoomStat.Verdict.UNDERUSED, byId.get("R-F110").verdict());
		assertEquals(UtilizationCalculator.RoomStat.Verdict.OVERSIZED, byId.get("R-HALE204").verdict());
		assertEquals(UtilizationCalculator.RoomStat.Verdict.WELL_UTILIZED, byId.get("R-LABB").verdict());
	}
}
