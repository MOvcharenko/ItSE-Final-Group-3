package support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import support.ScheduleModel.Meeting;

/**
 * Proves that TD-1 loads, resets cleanly, and contains what the test plan says it contains. No Spring: plain JDBC on
 * H2 (or PostgreSQL, see TestDatabase). These are facts about the DATA; they do not test the conflict engine.
 */
class Td1LoaderTest {

	static Connection db;

	@BeforeAll
	static void open() throws Exception {
		db = TestDatabase.open();
	}

	@AfterAll
	static void close() throws Exception {
		db.close();
	}

	@BeforeEach
	void freshTd1() throws Exception {
		Td1Loader.resetAndLoad(db);
	}

	private static int count(String table) throws Exception {
		try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static List<String> column(String sql) throws Exception {
		List<String> out = new ArrayList<>();
		try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	// ---------------- loading and resetting ----------------

	@Test
	@DisplayName("TP3: TD-1 has the term, 7 sections, 5 rooms, 4 equipment types and the TP3 accounts")
	void shape() throws Exception {
		assertEquals(List.of("Fall 2026"), column("SELECT name FROM term WHERE status = 'DRAFT'"));
		assertEquals(7, count("section"));
		assertEquals(5, count("room"));
		assertEquals(4, count("equipment"));
		assertEquals(7, count("subject"));
		assertEquals(List.of("CS 201", "CS 340", "ART 220", "BIO 150", "ENG 101", "MATH 210", "PHYS 110").stream().sorted().toList(),
				column("SELECT course_code FROM subject ORDER BY course_code"));
		assertEquals(List.of("Maya Brooks", "L. Bianchi"),
				column("SELECT display_name FROM app_user WHERE id IN ('U01','U02') ORDER BY id"));
		assertEquals(0, count("conflict")); // conflicts are DETECTED, never pre-loaded
	}

	@Test
	@DisplayName("TP3: reset empties every table, including rows other streams add during a test, in foreign-key order")
	void resetEmptiesEverything() throws Exception {
		try (Statement st = db.createStatement()) {
			st.executeUpdate("INSERT INTO conflict (id, term_id, conflict_type, severity, explanation, fingerprint, detected_at, room_id) "
					+ "VALUES ('C-X','T-2026-FALL','ROOM_DOUBLE_BOOKING','HIGH','x','fp-1','2026-10-24 12:00:00','R-LABB')");
			st.executeUpdate("INSERT INTO conflict_section VALUES ('C-X','SEC-106')");
			st.executeUpdate("INSERT INTO audit_log_entry VALUES ('AU-X','C-X','ROOM_DOUBLE_BOOKING','x','U01','2026-10-24 12:05:00','special event')");
			st.executeUpdate("INSERT INTO insight_suggestion (id, term_id, axis, measure, room_id, related_conflict_id, message, created_at) "
					+ "VALUES ('I-X','T-2026-FALL','ROOM_CAPACITY','FILL','R-CC301','C-X','m','2026-10-24 12:00:00')");
			st.executeUpdate("INSERT INTO notice (id, recipient_id, term_id, section_id, trigger_event, message, created_at) "
					+ "VALUES ('N-X','U03','T-2026-FALL','SEC-101','SECTION_CHANGED','m','2026-10-24 12:00:00')");
			st.executeUpdate("INSERT INTO import_batch VALUES ('IB-X','f.csv','ROOMS','2026-10-24 12:00:00','U01')");
			st.executeUpdate("INSERT INTO import_row VALUES ('IB-X',1,'ADDED',NULL)");
			st.executeUpdate("INSERT INTO availability_submission VALUES ('T-2026-FALL','U04','note','2026-10-24 12:00:00')");
			st.executeUpdate("INSERT INTO instructor_equipment_need VALUES ('T-2026-FALL','U04','projector')");
			st.executeUpdate("INSERT INTO section_equipment (section_id, equipment_id) VALUES ('SEC-106','E-LAPTOP')");
			st.executeUpdate("INSERT INTO booking (id, requester_id, resource_type, equipment_id, start_time, end_time, purpose, created_at) "
					+ "VALUES ('B-X','U02','EQUIPMENT','E-LAPTOP','2026-10-26 13:00:00','2026-10-26 15:00:00','x','2026-10-24 12:00:00')");
		}
		for (String t : Td1Loader.TABLES_CHILD_FIRST) {
			assertTrue(count(t) > 0, t + " should hold data before the reset, otherwise this test proves nothing");
		}

		Td1Loader.reset(db);

		for (String t : Td1Loader.TABLES_CHILD_FIRST) {
			assertEquals(0, count(t), t + " must be empty after reset");
		}
	}

	@Test
	@DisplayName("TP3: the reset list names every table of the schema, so a new migration cannot be forgotten")
	void resetCoversEveryTable() throws Exception {
		Set<String> inSchema = new TreeSet<>();
		try (ResultSet rs = db.getMetaData().getTables(null, null, "%", new String[] { "TABLE" })) {
			while (rs.next()) {
				String schema = rs.getString("TABLE_SCHEM");
				String name = rs.getString("TABLE_NAME").toLowerCase();
				if ("public".equalsIgnoreCase(schema) && !name.startsWith("flyway")) {
					inSchema.add(name);
				}
			}
		}
		assertEquals(inSchema, new TreeSet<>(Td1Loader.TABLES_CHILD_FIRST),
				"add new tables to Td1Loader.TABLES_CHILD_FIRST (children before parents)");
	}

	@Test
	void loadingTwiceAfterResetGivesTheSameData() throws Exception {
		int sections = count("section"), meetings = count("section_meeting"), blocks = count("instructor_availability");
		Td1Loader.resetAndLoad(db);
		assertEquals(sections, count("section"));
		assertEquals(meetings, count("section_meeting"));
		assertEquals(blocks, count("instructor_availability"));
	}

	@Test
	@DisplayName("TR3.4: Maya Brooks may manage every department the test steps touch")
	void mayaManagesEveryDepartmentOfTheTestCases() throws Exception {
		Set<String> mine = new HashSet<>(column("SELECT department FROM scheduler_department WHERE scheduler_id = 'U01'"));
		Set<String> used = new HashSet<>(column("SELECT DISTINCT department FROM subject"));
		used.addAll(Set.of("HIST", "PSY")); // TC-FR3.1 and TC-FR3.4a
		assertTrue(mine.containsAll(used), "missing: " + used.stream().filter(d -> !mine.contains(d)).toList());
	}

	@Test
	@DisplayName("TC-FR2.1 / TC-FR2.2: rooms carry fixed equipment, portable equipment is separate, two Laptop carts")
	void catalogs() throws Exception {
		assertEquals(List.of("Projector"), column("SELECT item_type FROM room_fixed_equipment WHERE room_id = 'R-HALE204'"));
		assertEquals(List.of("2"), column("SELECT unit_count FROM equipment WHERE name = 'Laptop cart'"));
		assertEquals(4, column("SELECT DISTINCT item_type FROM equipment").size());
		// TC-FR2.4 needs several rooms with capacity >= 20 and a projector
		assertTrue(column("SELECT r.id FROM room r JOIN room_fixed_equipment f ON f.room_id = r.id "
				+ "WHERE r.capacity >= 20 AND f.item_type = 'Projector'").size() >= 3);
		// names that test cases add by hand must not already exist
		assertEquals(0, column("SELECT name FROM room WHERE name = 'Innovation Lab C'").size());
		assertEquals(0, column("SELECT name FROM equipment WHERE name = 'Portable speaker'").size());
		assertEquals(0, column("SELECT course_code FROM subject WHERE course_code = 'PSY 220'").size());
		assertEquals(2, count("booking")); // history for TC-FR2.5, both in the past
	}

	// ---------------- what the test cases expect from the data ----------------

	@Test
	@DisplayName("TC-FR4.1-4.4, TC-FR4.7: TD-1 contains EXACTLY the three known conflicts")
	void knownConflicts() throws Exception {
		Set<String> found = ScheduleModel.conflicts(ScheduleModel.meetings(db), ScheduleModel.blocks(db), Td1.ENROLLED);

		assertEquals(new TreeSet<>(List.of(
				"ROOM_DOUBLE_BOOKING ART 220 + CS 340 in Innovation Lab B",   // TC-FR4.3a
				"INSTRUCTOR_UNAVAILABLE CS 340 U04",                          // TC-FR4.3c
				"CAPACITY_EXCEEDED ENG 101 in Carter Center 301")),           // TC-FR4.3e
				found);
	}

	@Test
	@DisplayName("TC-FR4.3f precondition: MATH 210 and PHYS 110 overlap on Tuesday, different rooms and instructors")
	void studentGroupClashPrecondition() throws Exception {
		List<Meeting> all = ScheduleModel.meetings(db);
		Meeting math = all.stream().filter(m -> m.course().equals("MATH 210") && m.day().name().equals("TUESDAY")).findFirst().orElseThrow();
		Meeting phys = all.stream().filter(m -> m.course().equals("PHYS 110")).findFirst().orElseThrow();
		assertTrue(ScheduleModel.overlap(math.day(), math.start(), math.end(), phys.day(), phys.start(), phys.end()));
		assertFalse(math.roomId().equals(phys.roomId()));
		assertTrue(math.instructors().stream().noneMatch(phys.instructors()::contains));
	}

	@Test
	@DisplayName("TC-FR5.1: Bianchi 6 of 18 h, Novak 2 of 8, Alvarez 9 of 10, the others mid-range; nobody else has a bar")
	void instructorUtilization() throws Exception {
		List<Meeting> all = ScheduleModel.meetings(db);
		Map<String, Integer> scheduled = ScheduleModel.scheduledMinutes(all);
		Map<String, Integer> declared = ScheduleModel.declaredMinutes(ScheduleModel.blocks(db));

		assertEquals(6 * 60, scheduled.get(Td1.L_BIANCHI));
		assertEquals(18 * 60, declared.get(Td1.L_BIANCHI));
		assertEquals(2 * 60, scheduled.get(Td1.DR_NOVAK));
		assertEquals(8 * 60, declared.get(Td1.DR_NOVAK));
		assertEquals(9 * 60, scheduled.get(Td1.PROF_ALVAREZ));
		assertEquals(10 * 60, declared.get(Td1.PROF_ALVAREZ));

		// with the proposed thresholds (OI-3): suggestion below 50%, confirmation from 80%
		Map<String, String> verdict = new TreeMap<>();
		declared.forEach((who, d) -> {
			double load = scheduled.getOrDefault(who, 0) / (double) d;
			verdict.put(who, load < 0.5 ? "SUGGESTION" : load >= 0.8 ? "CONFIRMATION" : "NONE");
		});
		assertEquals(Map.of(Td1.L_BIANCHI, "SUGGESTION", Td1.DR_NOVAK, "SUGGESTION", Td1.PROF_ALVAREZ, "CONFIRMATION",
				Td1.DR_HADDAD, "NONE", Td1.PROF_OKAFOR, "NONE"), verdict);
		assertFalse(declared.containsKey(Td1.DR_SUZUKI)); // declared nothing: no bar, no 0% suggestion
		assertFalse(declared.containsKey(Td1.DR_PATEL));
	}

	@Test
	@DisplayName("TC-FR5.2: Founders 110 is underused, Hale Hall 204 oversized, Innovation Lab B well utilized")
	void roomUtilization() throws Exception {
		List<Meeting> all = ScheduleModel.meetings(db);
		Map<String, Double> occupancy = new TreeMap<>();
		Map<String, List<Double>> fills = new TreeMap<>();
		for (Meeting m : all) {
			occupancy.merge(m.roomName(), m.minutes() / (40.0 * 60), Double::sum); // share of a 40-hour week
		}
		Set<String> seen = new HashSet<>();
		for (Meeting m : all) {
			if (seen.add(m.sectionId())) {
				fills.computeIfAbsent(m.roomName(), k -> new ArrayList<>()).add(Td1.ENROLLED.get(m.sectionId()) / (double) m.roomCapacity());
			}
		}

		Map<String, String> label = new TreeMap<>();
		occupancy.forEach((room, occ) -> {
			double fill = fills.get(room).stream().mapToDouble(Double::doubleValue).average().orElse(0);
			label.put(room, occ < 0.05 ? "UNDERUSED" : fill < 0.40 ? "OVERSIZED" : "WELL_UTILIZED");
		});

		assertEquals("UNDERUSED", label.get("Founders 110"));
		assertEquals("OVERSIZED", label.get("Hale Hall 204"));
		assertEquals("WELL_UTILIZED", label.get("Innovation Lab B"));
		assertEquals("WELL_UTILIZED", label.get("Carter Center 301")); // also carries the capacity conflict (TC-FR5.4)
		assertEquals("WELL_UTILIZED", label.get("Sci Building 02"));
	}

	@Test
	@DisplayName("TC-FR1.4: Dr. Novak's Unavailable window lies over the CS 340 block it clashes with")
	void availabilityOverlay() throws Exception {
		var unavailable = ScheduleModel.blocks(db).stream().filter(b -> b.status().equals("UNAVAILABLE")).toList();
		assertEquals(1, unavailable.size());
		assertEquals(Td1.DR_NOVAK, unavailable.get(0).instructorId());
		assertEquals("12:00", unavailable.get(0).start().toString());
		assertEquals("14:00", unavailable.get(0).end().toString());
	}
}
