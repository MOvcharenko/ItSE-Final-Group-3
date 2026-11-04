package support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import core.importing.ImportColumns;
import core.importing.ImportFileParser;
import core.importing.ImportRows.ScheduleRow;
import core.importing.Keys;
import core.importing.ParsedFile;
import core.importing.RowResult;
import domain.ImportType;
import support.ScheduleModel.Meeting;

/**
 * TD-2 (import files) checked against the parser and against TD-1. Order of the plan matters: HIST 120, HIST 210 and
 * CHEM 101 enter the catalog through subjects.csv (TC-FR3.3) before schedule.csv places them (TC-FR3.4a).
 */
class Td2FilesTest {

	static Connection db;
	static Map<String, String[]> roomsByKey = new HashMap<>();      // key -> {id, name, capacity}
	static Map<String, String> instructorsByKey = new HashMap<>();  // key -> id
	static Set<String> td1Subjects = new TreeSet<>();

	@BeforeAll
	static void load() throws Exception {
		db = TestDatabase.open();
		Td1Loader.resetAndLoad(db);
		try (Statement st = db.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT id, name, capacity FROM room")) {
				while (rs.next()) {
					roomsByKey.put(Keys.of(rs.getString(2)), new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT id, display_name FROM app_user")) {
				while (rs.next()) {
					instructorsByKey.put(Keys.of(rs.getString(2)), rs.getString(1));
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT course_code FROM subject")) {
				while (rs.next()) {
					td1Subjects.add(rs.getString(1));
				}
			}
		}
	}

	@AfterAll
	static void close() throws Exception {
		db.close();
	}

	static String read(String file) throws IOException {
		try (InputStream in = Td2FilesTest.class.getResourceAsStream("/td2/" + file)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static <T> List<T> values(ParsedFile<T> file) {
		assertFalse(file.isRejected(), file.fileError());
		List<T> out = new ArrayList<>();
		for (RowResult<T> r : file.rows()) {
			out.add(((RowResult.Valid<T>) assertInstanceOf(RowResult.Valid.class, r, r.toString())).value());
		}
		return out;
	}

	// ---------------- the four filled templates (TC-FR3.3, TC-FR3.5) ----------------

	@Test
	@DisplayName("TC-FR3.3 / TC-FR3.5: the four filled templates start with the exact header and every row is valid")
	void filledTemplatesAreClean() throws Exception {
		assertEquals(2, values(ImportFileParser.rooms(read("rooms.csv"))).size());
		assertEquals(2, values(ImportFileParser.equipment(read("equipment.csv"))).size());
		assertEquals(3, values(ImportFileParser.subjects(read("subjects.csv"))).size());
		assertEquals(2, values(ImportFileParser.schedule(read("schedule.csv"))).size());
		assertEquals(1, values(ImportFileParser.schedule(read("schedule-lab-b-monday.csv"))).size());

		assertTrue(read("rooms.csv").startsWith(ImportColumns.headerLine(ImportType.ROOMS) + "\n"));
		assertTrue(read("equipment.csv").startsWith(ImportColumns.headerLine(ImportType.EQUIPMENT) + "\n"));
		assertTrue(read("subjects.csv").startsWith(ImportColumns.headerLine(ImportType.SUBJECT_LIST) + "\n"));
		assertTrue(read("schedule.csv").startsWith(ImportColumns.headerLine(ImportType.READY_MADE_SCHEDULE) + "\n"));
	}

	@Test
	@DisplayName("TC-FR3.3: a quoted location with a comma survives (\"AV Storage, Hale Hall\")")
	void quotedLocation() throws Exception {
		var rows = values(ImportFileParser.equipment(read("equipment.csv")));
		assertEquals("AV Storage, Hale Hall", rows.get(0).homeLocation());
	}

	@Test
	@DisplayName("TC-FR3.1 / TC-FR3.2 / TC-FR3.3: TD-2 never collides with TD-1 or with what test cases add by hand")
	void noNameCollisions() throws Exception {
		for (var r : values(ImportFileParser.rooms(read("rooms.csv")))) {
			assertFalse(roomsByKey.containsKey(Keys.of(r.name())), "room already in TD-1: " + r.name());
			assertFalse(Keys.of(r.name()).equals(Keys.of("Innovation Lab C")), "added by hand in TC-FR3.2");
		}
		for (var s : values(ImportFileParser.subjects(read("subjects.csv")))) {
			assertFalse(td1Subjects.contains(s.courseCode()), "subject already in TD-1: " + s.courseCode());
			assertFalse(s.courseCode().equals("PSY 220"), "added by hand in TC-FR3.1");
		}
		for (var e : values(ImportFileParser.equipment(read("equipment.csv")))) {
			assertFalse(e.name().equalsIgnoreCase("Portable speaker"), "added by hand in TC-FR3.2");
			try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(
					"SELECT COUNT(*) FROM equipment WHERE LOWER(name) = '" + e.name().toLowerCase() + "'")) {
				rs.next();
				assertEquals(0, rs.getInt(1), "equipment already in TD-1: " + e.name());
			}
		}
	}

	// ---------------- ready-made schedule files against TD-1 (TC-FR3.4a, TC-FR3.7) ----------------

	private static List<Meeting> asMeetings(ParsedFile<ScheduleRow> file) {
		List<Meeting> out = new ArrayList<>();
		for (ScheduleRow row : values(file)) {
			String[] room = roomsByKey.get(Keys.of(row.roomName()));
			Set<String> who = new TreeSet<>();
			for (String name : row.instructorNames()) {
				who.add(instructorsByKey.get(Keys.of(name)));
			}
			for (var day : row.days()) {
				out.add(new Meeting("NEW-" + row.courseCode(), row.courseCode(), room[0], room[1], Integer.parseInt(room[2]),
						day, row.start(), row.end(), who));
			}
		}
		return out;
	}

	private static Set<String> conflictsWith(List<Meeting> extra) throws Exception {
		List<Meeting> all = new ArrayList<>(ScheduleModel.meetings(db));
		all.addAll(extra);
		return ScheduleModel.conflicts(all, ScheduleModel.blocks(db), Td1.ENROLLED);
	}

	private static final Set<String> KNOWN = new TreeSet<>(List.of(
			"ROOM_DOUBLE_BOOKING ART 220 + CS 340 in Innovation Lab B",
			"INSTRUCTOR_UNAVAILABLE CS 340 U04",
			"CAPACITY_EXCEEDED ENG 101 in Carter Center 301"));

	@Test
	@DisplayName("TC-FR3.4a: schedule.csv names rooms and instructors that exist in TD-1 and adds no conflict")
	void validScheduleFitsTd1() throws Exception {
		var rows = values(ImportFileParser.schedule(read("schedule.csv")));
		Set<String> subjectsFile = new TreeSet<>();
		values(ImportFileParser.subjects(read("subjects.csv"))).forEach(s -> subjectsFile.add(s.courseCode()));
		for (ScheduleRow r : rows) {
			assertTrue(roomsByKey.containsKey(Keys.of(r.roomName())), "unknown room " + r.roomName());
			r.instructorNames().forEach(n -> assertTrue(instructorsByKey.containsKey(Keys.of(n)), "unknown instructor " + n));
			assertTrue(subjectsFile.contains(r.courseCode()), r.courseCode() + " must arrive through subjects.csv first");
			assertEquals("Fall2026", r.termName()); // matches the term 'Fall 2026' through Keys.of
		}
		assertEquals(KNOWN, conflictsWith(asMeetings(ImportFileParser.schedule(read("schedule.csv")))));
	}

	@Test
	@DisplayName("TC-FR3.7: the Lab B Monday row raises a Room double-booking with BOTH CS 340 and ART 220, at once")
	void labBMondayRowRaisesARoomDoubleBooking() throws Exception {
		Set<String> found = conflictsWith(asMeetings(ImportFileParser.schedule(read("schedule-lab-b-monday.csv"))));
		Set<String> added = new TreeSet<>(found);
		added.removeAll(KNOWN);
		assertEquals(new TreeSet<>(List.of(
				"ROOM_DOUBLE_BOOKING CHEM 101 + CS 340 in Innovation Lab B",
				"ROOM_DOUBLE_BOOKING ART 220 + CHEM 101 in Innovation Lab B")), added);
	}

	// ---------------- the faulty file (TC-FR3.6, TR4.6) ----------------

	@Test
	@DisplayName("TC-FR3.6: the faulty file is not rejected; one valid row, a duplicate code, a missing field, an unknown room")
	void faultyFile() throws Exception {
		ParsedFile<ScheduleRow> file = ImportFileParser.schedule(read("schedule-faulty.csv"));
		assertFalse(file.isRejected());
		assertEquals(4, file.rows().size());

		// row 3: the parser sees the missing required field
		var missing = assertInstanceOf(RowResult.Invalid.class, file.rows().get(2));
		assertEquals("Missing required field: title", missing.reason());
		assertEquals(3, missing.rowNumber());

		// rows 1, 2 and 4 are well-formed; the SERVICE decides the rest against the catalog and the timetable
		ScheduleRow ok = ((RowResult.Valid<ScheduleRow>) file.rows().get(0)).value();
		ScheduleRow duplicate = ((RowResult.Valid<ScheduleRow>) file.rows().get(1)).value();
		ScheduleRow unknownRoom = ((RowResult.Valid<ScheduleRow>) file.rows().get(3)).value();

		// row 1: everything it names exists, and placing it changes nothing about the conflicts
		assertTrue(roomsByKey.containsKey(Keys.of(ok.roomName())));
		assertTrue(instructorsByKey.containsKey(Keys.of(ok.instructorNames().get(0))));
		assertEquals(KNOWN, conflictsWith(List.of(new Meeting("NEW", ok.courseCode(),
				roomsByKey.get(Keys.of(ok.roomName()))[0], ok.roomName(), 30, ok.days().get(0), ok.start(), ok.end(),
				Set.of(instructorsByKey.get(Keys.of("Dr. Patel")))))));
		// row 2: CS 340 is already placed in Fall 2026, so it is a duplicate subject code
		assertTrue(td1Subjects.contains(duplicate.courseCode()));
		assertTrue(ScheduleModel.meetings(db).stream().anyMatch(m -> m.course().equals(duplicate.courseCode())));
		// row 4: the room is not in the catalog ("room not recognized")
		assertFalse(roomsByKey.containsKey(Keys.of(unknownRoom.roomName())));
	}
}
