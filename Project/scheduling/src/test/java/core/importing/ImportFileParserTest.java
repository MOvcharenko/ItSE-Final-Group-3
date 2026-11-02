package core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import core.importing.ImportRows.EquipmentRow;
import core.importing.ImportRows.RoomRow;
import core.importing.ImportRows.ScheduleRow;
import core.importing.ImportRows.SubjectRow;
import domain.ImportType;
import domain.RoomType;

class ImportFileParserTest {

	private static final String ROOMS_HEADER = "room_name,building,capacity,room_type,equipment\n";
	private static final String EQUIPMENT_HEADER = "item_name,type,units,home_location\n";
	private static final String SUBJECTS_HEADER = "subject_code,title,credit_hours,default_room_type,equipment_needed\n";
	private static final String SCHEDULE_HEADER = "subject_code,title,days,start_time,end_time,room,instructor,term\n";

	private static <T> T valid(RowResult<T> r) {
		return assertInstanceOf(RowResult.Valid.class, r, r.toString()) instanceof RowResult.Valid<?> v ? castValue(v) : null;
	}

	@SuppressWarnings("unchecked")
	private static <T> T castValue(RowResult.Valid<?> v) {
		return (T) v.value();
	}

	private static String reason(RowResult<?> r) {
		return assertInstanceOf(RowResult.Invalid.class, r, r.toString()) instanceof RowResult.Invalid<?> i ? i.reason() : null;
	}

	// ---------------- headers ----------------

	@ParameterizedTest
	@EnumSource(ImportType.class)
	@DisplayName("TC-FR3.5: a file that starts with the exact template header is accepted, for all four types")
	void exactHeaderIsAccepted(ImportType type) {
		assertTrue(ImportColumns.checkHeader(type, ImportColumns.of(type)).isEmpty());
	}

	@Test
	@DisplayName("TC-FR3.5: the User's Guide shows headers with a space after each comma; that still matches")
	void spacesAndCaseInTheHeaderAreTolerated() {
		var file = ImportFileParser.rooms("Room_Name, Building, Capacity, Room_Type, Equipment\nFounders 110,Founders,60,Lecture hall,\n");
		assertFalse(file.isRejected(), file.fileError());
		assertEquals(1, file.rows().size());
	}

	@Test
	@DisplayName("TC-FR3.5: an altered header rejects the whole file, names expected and found columns, reads no row")
	void alteredHeaderRejectsTheFile() {
		var file = ImportFileParser.rooms("room_name,building,seats,room_type,equipment\nFounders 110,Founders,60,Lecture hall,\n");
		assertTrue(file.isRejected());
		assertTrue(file.rows().isEmpty());
		assertTrue(file.fileError().contains("capacity"), file.fileError());
		assertTrue(file.fileError().contains("seats"), file.fileError());
		assertTrue(file.fileError().contains("rooms template"), file.fileError());
	}

	@Test
	void aFileUploadedAsTheWrongTypeIsRejectedByItsHeader() {
		var file = ImportFileParser.equipment(ROOMS_HEADER + "Founders 110,Founders,60,Lecture hall,\n");
		assertTrue(file.isRejected());
	}

	@Test
	void columnsInTheWrongOrderAreRejected() {
		assertTrue(ImportFileParser.rooms("building,room_name,capacity,room_type,equipment\n").isRejected());
	}

	@Test
	void emptyFileIsRejectedWithAHelpfulMessage() {
		var file = ImportFileParser.rooms("  \n");
		assertTrue(file.isRejected());
		assertTrue(file.fileError().contains("empty"));
	}

	@Test
	void headerOnlyFileIsAcceptedWithNoRows() {
		var file = ImportFileParser.rooms(ROOMS_HEADER);
		assertFalse(file.isRejected());
		assertTrue(file.rows().isEmpty());
	}

	@Test
	void brokenQuotingRejectsTheFile() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + "\"Founders 110,Founders,60,Lecture hall,\n");
		assertTrue(file.isRejected());
		assertTrue(file.fileError().startsWith("Line 2:"), file.fileError());
	}

	// ---------------- valid rows of each type (TC-FR3.3) ----------------

	@Test
	@DisplayName("TC-FR3.3: rooms")
	void validRoomRow() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + "Innovation Lab C,Innovation,30,Lab,Projector; Whiteboard\n");
		RoomRow row = valid(file.rows().get(0));
		assertEquals(new RoomRow("Innovation Lab C", "Innovation", 30, RoomType.LAB, List.of("Projector", "Whiteboard")), row);
		assertEquals(1, file.rows().get(0).rowNumber());
	}

	@Test
	@DisplayName("TC-FR3.3 / User's Guide s.5: equipment with a quoted location that contains a comma")
	void validEquipmentRowWithQuotedLocation() {
		var file = ImportFileParser.equipment(EQUIPMENT_HEADER + "Portable speaker,Speaker,3,\"AV Storage, Hale Hall\"\n");
		EquipmentRow row = valid(file.rows().get(0));
		assertEquals(new EquipmentRow("Portable speaker", "Speaker", 3, "AV Storage, Hale Hall"), row);
	}

	@Test
	@DisplayName("TC-FR3.2: equipment without a home location is fine (the field is optional)")
	void equipmentHomeLocationIsOptional() {
		var file = ImportFileParser.equipment(EQUIPMENT_HEADER + "Portable speaker,Speaker,3\n");
		EquipmentRow row = valid(file.rows().get(0));
		assertNull(row.homeLocation());
	}

	@Test
	@DisplayName("TC-FR3.3 / TC-FR3.4b: subject list, using the User's Guide example row literally")
	void validSubjectRowFromTheGuideExample() {
		var file = ImportFileParser.subjects(SUBJECTS_HEADER + "PSY 220,Cognitive Psychology,3,Lecture hall,(leave blank if none)\n");
		SubjectRow row = valid(file.rows().get(0));
		assertEquals(new SubjectRow("PSY 220", "Cognitive Psychology", 3, RoomType.LECTURE_HALL, List.of()), row);
	}

	@Test
	@DisplayName("TC-FR3.4a: ready-made schedule, using the User's Guide example row")
	void validScheduleRowFromTheGuideExample() {
		var file = ImportFileParser.schedule(SCHEDULE_HEADER + "CS 450,Advanced Algorithms,MWF,13:00,13:50,Founders 110,Dr. Patel,Fall2026\n");
		ScheduleRow row = valid(file.rows().get(0));
		assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), row.days());
		assertEquals(LocalTime.of(13, 0), row.start());
		assertEquals(LocalTime.of(13, 50), row.end());
		assertEquals("Founders 110", row.roomName());
		assertEquals(List.of("Dr. Patel"), row.instructorNames());
		assertEquals("Fall2026", row.termName());
	}

	@Test
	@DisplayName("FR1.6: several instructors on one imported section, separated by semicolons")
	void coInstructorsAreSplit() {
		var file = ImportFileParser.schedule(SCHEDULE_HEADER + "MATH 210,Calculus,TTh,10:00,11:15,Hale Hall 204,Prof. Bianchi; Dr. Novak,Fall2026\n");
		ScheduleRow row = valid(file.rows().get(0));
		assertEquals(List.of("Prof. Bianchi", "Dr. Novak"), row.instructorNames());
		assertEquals(List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), row.days());
	}

	// ---------------- faulty rows (TC-FR3.6, TR4.6) ----------------

	@Test
	@DisplayName("TC-FR3.6: a partly faulty file is NOT rejected; every row gets its own outcome and reason")
	void faultyFileGivesPerRowOutcomes() {
		String csv = SCHEDULE_HEADER
				+ "HIST 120,World History,MWF,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n"      // 1 valid
				+ "PSY 220,,TTh,10:00,11:15,Founders 110,Dr. Patel,Fall2026\n"                       // 2 missing title
				+ "CS 450L,Lab,F,14:00,16:00,Founders 110,Dr. Patel,Fall2026,extra\n"                // 3 too many columns
				+ "ART 101,Drawing,M,15:00,14:00,Hale Hall 204,Dr. Patel,Fall2026\n";                // 4 end before start
		var file = ImportFileParser.schedule(csv);

		assertFalse(file.isRejected());
		assertEquals(4, file.rows().size());
		assertInstanceOf(RowResult.Valid.class, file.rows().get(0));
		assertEquals("Missing required field: title", reason(file.rows().get(1)));
		assertTrue(reason(file.rows().get(2)).startsWith("Expected 8 columns but found 9"), reason(file.rows().get(2)));
		assertEquals("start_time must be before end_time", reason(file.rows().get(3)));
		assertEquals(List.of(1, 2, 3, 4), file.rows().stream().map(RowResult::rowNumber).toList());
	}

	@Test
	@DisplayName("TC-FR3.6: all problems of one row are reported together")
	void everyProblemOfARowIsListed() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + ",Founders,abc,Cafe,\n");
		String why = reason(file.rows().get(0));
		assertTrue(why.contains("Missing required field: room_name"), why);
		assertTrue(why.contains("capacity must be a whole number"), why);
		assertTrue(why.contains("room_type 'Cafe' is not recognised"), why);
	}

	@Test
	@DisplayName("TC-FR3.2: capacity -5 and 0 are refused with a reason")
	void nonPositiveCapacityIsRefused() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + "Innovation Lab C,Innovation,-5,Lab,\nLab D,Innovation,0,Lab,\n");
		assertTrue(reason(file.rows().get(0)).contains("capacity must be at least 1"));
		assertTrue(reason(file.rows().get(1)).contains("capacity must be at least 1"));
	}

	@Test
	@DisplayName("An unquoted comma inside the location shifts the columns and is reported as such (User's Guide s.5)")
	void unquotedCommaGivesAColumnCountMessage() {
		var file = ImportFileParser.equipment(EQUIPMENT_HEADER + "Portable speaker,Speaker,3,AV Storage, Hale Hall\n");
		assertTrue(reason(file.rows().get(0)).contains("wrap it in double quotes"));
	}

	@Test
	void rowShorterThanTheHeaderReadsMissingTrailingCellsAsBlank() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + "Founders 110,Founders,60,Lecture hall\n"); // equipment omitted
		assertInstanceOf(RowResult.Valid.class, file.rows().get(0));
		var tooShort = ImportFileParser.rooms(ROOMS_HEADER + "Founders 110,Founders\n");
		String why = reason(tooShort.rows().get(0));
		assertTrue(why.contains("Missing required field: capacity") && why.contains("room_type"), why);
	}

	@Test
	void valuesLongerThanTheDatabaseColumnAreCaughtBeforeTheDatabase() {
		String longName = "x".repeat(101);
		var file = ImportFileParser.rooms(ROOMS_HEADER + longName + ",B,10,Lab,\n");
		assertTrue(reason(file.rows().get(0)).contains("room_name is too long (100 characters at most)"));
	}

	@Test
	void absurdNumbersDoNotCrashTheParser() {
		var file = ImportFileParser.rooms(ROOMS_HEADER + "R,B,99999999999999999999,Lab,\n");
		assertTrue(reason(file.rows().get(0)).contains("whole number"));
	}

	// ---------------- cells ----------------

	@Test
	void roomTypeAcceptsGuideWordingAndEnumNames() {
		for (String t : List.of("Lecture hall", "lecture hall", "LECTURE_HALL", "Lecture-Hall", " lecture  hall ")) {
			var file = ImportFileParser.subjects(SUBJECTS_HEADER + "X 1,T,3," + t + ",\n");
			SubjectRow row = valid(file.rows().get(0));
			assertEquals(RoomType.LECTURE_HALL, row.defaultRoomType(), t);
		}
		SubjectRow seminar = valid(ImportFileParser.subjects(SUBJECTS_HEADER + "X 1,T,3,Seminar room,\n").rows().get(0));
		assertEquals(RoomType.SEMINAR_ROOM, seminar.defaultRoomType());
	}

	@Test
	void dayCodes() {
		assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
				days("MTWThF"));
		assertEquals(List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), days("TTh"));
		assertEquals(List.of(DayOfWeek.THURSDAY), days("Th"));
		assertEquals(List.of(DayOfWeek.THURSDAY), days("TH"));
		assertEquals(List.of(DayOfWeek.TUESDAY), days("T"));
		assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), days("M F"));
	}

	@Test
	void badDayCodesAreExplained() {
		assertTrue(dayProblem("MXF").contains("unknown day code 'X'. Use M, T, W, Th, F"));
		assertTrue(dayProblem("MM").contains("twice"));
		assertTrue(dayProblem("H").contains("unknown day code 'H'"));
	}

	@Test
	void times() {
		assertEquals(LocalTime.of(9, 30), time("9:30"));
		assertEquals(LocalTime.of(9, 30), time("09:30"));
		assertEquals(LocalTime.of(0, 0), time("0:00"));
		for (String bad : List.of("1:00 pm", "25:00", "13:60", "1300", "noon")) {
			var file = ImportFileParser.schedule(SCHEDULE_HEADER + "A 1,T,M," + bad + ",23:00,R,I,Fall2026\n");
			assertTrue(reason(file.rows().get(0)).contains("is not a 24-hour time such as 13:00"), bad);
		}
	}

	@Test
	void duplicateListEntriesAreCollapsed() {
		RoomRow row = valid(ImportFileParser.rooms(ROOMS_HEADER + "R,B,10,Lab,Projector;Projector; ;Whiteboard\n").rows().get(0));
		assertEquals(List.of("Projector", "Whiteboard"), row.fixedEquipment());
	}

	@Test
	void scheduleRowNeedsAnInstructor() {
		var file = ImportFileParser.schedule(SCHEDULE_HEADER + "A 1,T,M,9:00,10:00,R,,Fall2026\n");
		assertEquals("Missing required field: instructor", reason(file.rows().get(0)));
	}

	@Test
	@DisplayName("Keys: 'Fall2026' finds 'Fall 2026'; case and spacing never matter for lookups")
	void matchingKeys() {
		assertEquals(Keys.of("Fall 2026"), Keys.of("Fall2026"));
		assertEquals(Keys.of("Founders 110"), Keys.of("  founders   110 "));
		assertFalse(Keys.of("Founders 110").equals(Keys.of("Founders 111")));
	}

	// ---------------- helpers ----------------

	private static List<DayOfWeek> days(String code) {
		ScheduleRow row = valid(ImportFileParser.schedule(SCHEDULE_HEADER + "A 1,T," + code + ",9:00,10:00,R,I,Fall2026\n").rows().get(0));
		return row.days();
	}

	private static String dayProblem(String code) {
		return reason(ImportFileParser.schedule(SCHEDULE_HEADER + "A 1,T," + code + ",9:00,10:00,R,I,Fall2026\n").rows().get(0));
	}

	private static LocalTime time(String t) {
		ScheduleRow row = valid(ImportFileParser.schedule(SCHEDULE_HEADER + "A 1,T,M," + t + ",23:00,R,I,Fall2026\n").rows().get(0));
		return row.start();
	}
}
