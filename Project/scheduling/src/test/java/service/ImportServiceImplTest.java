package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;

import domain.ImportType;
import domain.RoomType;
import domain.RowOutcome;
import integration.StaffDirectory;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.ImportBatchEntity;
import persistence.ImportBatchRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import security.CurrentUser;
import service.view.ImportBatchView;

class ImportServiceImplTest {

	static final String ROOMS = "room_name,building,capacity,room_type,equipment\n";
	static final String SUBJECTS = "subject_code,title,credit_hours,default_room_type,equipment_needed\n";
	static final String SCHEDULE = "subject_code,title,days,start_time,end_time,room,instructor,term\n";

	CatalogService catalog = mock(CatalogService.class);
	ImportBatchRepository batches = mock(ImportBatchRepository.class);
	RoomRepository rooms = mock(RoomRepository.class);
	SubjectRepository subjects = mock(SubjectRepository.class);
	AppUserRepository users = mock(AppUserRepository.class);
	@SuppressWarnings("unchecked")
	ObjectProvider<SchedulePlacement> placement = mock(ObjectProvider.class);
	SchedulePlacement timetable = mock(SchedulePlacement.class);
	UserProfiles profiles = mock(UserProfiles.class);
	StaffDirectory staffDirectory = mock(StaffDirectory.class);
	Clock clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);

	@BeforeEach
	void defaults() {
		when(batches.save(any(ImportBatchEntity.class))).thenAnswer(i -> i.getArgument(0));
		when(placement.getIfAvailable()).thenReturn(timetable);
		when(rooms.findAll()).thenReturn(List.of(
				new RoomEntity("R1", "Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of()),
				new RoomEntity("R2", "Hale Hall 204", "Hale", 120, RoomType.LECTURE_HALL, Set.of())));
		when(subjects.findAll()).thenReturn(List.of(
				new SubjectEntity("S1", "HIST 120", "World History", 3, RoomType.LECTURE_HALL, "HIST", Set.of()),
				new SubjectEntity("S2", "PSY 220", "Cognitive Psychology", 3, RoomType.LECTURE_HALL, null, Set.of()),
				new SubjectEntity("S3", "CS 450L", "Algorithms Lab", 1, RoomType.LAB, "CS", Set.of())));
		when(users.findByDisplayNameIgnoreCase("Dr. Patel"))
				.thenReturn(List.of(new AppUserEntity("U10", "Dr. Patel", null, "CS")));
	}

	private ImportServiceImpl as(CurrentUser who) {
		return new ImportServiceImpl(catalog, batches, rooms, subjects, users, placement, profiles,
				TestUsers.caller(who), staffDirectory, clock);
	}

	private ImportBatchEntity savedBatch() {
		ArgumentCaptor<ImportBatchEntity> c = ArgumentCaptor.forClass(ImportBatchEntity.class);
		verify(batches).save(c.capture());
		return c.getValue();
	}

	// ---------------- header / file level ----------------

	@Test
	@DisplayName("TC-FR3.5 / UC-12 5a: a wrong header rejects the whole file, processes no row and records nothing")
	void wrongHeaderRejectsTheFile() {
		var e = assertThrows(BusinessRuleException.class, () -> as(TestUsers.MAYA).importFile("rooms.csv",
				ImportType.ROOMS, "room_name,building,seats,room_type,equipment\nFounders 110,Founders,60,Lecture hall,\n"));
		assertTrue(e.getMessage().contains("rooms template"), e.getMessage());
		verify(catalog, never()).addRoom(anyString(), anyString(), anyInt(), any(), anyList());
		verify(batches, never()).save(any(ImportBatchEntity.class));
	}

	@Test
	void brokenOrEmptyFilesAreRejected() {
		assertTrue(assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).importFile("x.csv", ImportType.ROOMS, "")).getMessage().contains("empty"));
		assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).importFile("x.csv", ImportType.ROOMS, ROOMS + "\"unclosed,B,1,Lab,\n"));
		verify(batches, never()).save(any(ImportBatchEntity.class));
	}

	@Test
	@DisplayName("TR3.2 / TR3.3: only a Scheduler can import or download a template")
	void onlySchedulersImport() {
		for (CurrentUser who : List.of(TestUsers.BIANCHI, TestUsers.STUDENT)) {
			assertThrows(AccessDeniedException.class, () -> as(who).importFile("r.csv", ImportType.ROOMS, ROOMS));
			assertThrows(AccessDeniedException.class, () -> as(who).getTemplate(ImportType.ROOMS));
		}
		verifyNoMoreInteractions(catalog, batches);
	}

	@Test
	@DisplayName("TC-FR3.5: the template is the exact header line")
	void template() {
		assertEquals("room_name,building,capacity,room_type,equipment\n", as(TestUsers.MAYA).getTemplate(ImportType.ROOMS));
		assertEquals("item_name,type,units,home_location\n", as(TestUsers.MAYA).getTemplate(ImportType.EQUIPMENT));
	}

	// ---------------- catalogs: per-row outcomes ----------------

	@Test
	@DisplayName("TC-FR3.3: every valid row of a room file goes through CatalogService and is Added; the batch is recorded")
	void validRoomFile() {
		ImportBatchView v = as(TestUsers.MAYA).importFile("rooms.csv", ImportType.ROOMS,
				ROOMS + "Innovation Lab C,Innovation,30,Lab,Projector; Whiteboard\nFounders 112,Founders,45,Lecture hall,\n");

		assertEquals(2, v.added());
		assertEquals(0, v.needsReview());
		verify(catalog).addRoom("Innovation Lab C", "Innovation", 30, RoomType.LAB, List.of("Projector", "Whiteboard"));
		verify(catalog).addRoom("Founders 112", "Founders", 45, RoomType.LECTURE_HALL, List.of());
		ImportBatchEntity b = savedBatch();
		assertEquals("rooms.csv", b.getFileName());
		assertEquals(ImportType.ROOMS, b.getImportType());
		assertEquals("U01", b.getImportedBy());
		assertEquals(LocalDateTime.of(2026, 10, 24, 10, 0), b.getImportedAt());
		assertEquals(2, b.getRows().size());
	}

	@Test
	@DisplayName("TC-FR3.6 / UC-12 6a: a faulty file is NOT rejected; each row has its own outcome and reason, good rows stay")
	void faultyFileGivesPerRowOutcomes() {
		doThrow(new BusinessRuleException("Duplicate room name: 'Founders 110' already exists."))
				.when(catalog).addRoom(eq("Founders 110"), anyString(), anyInt(), any(), anyList());

		ImportBatchView v = as(TestUsers.MAYA).importFile("faulty.csv", ImportType.ROOMS, ROOMS
				+ "Innovation Lab C,Innovation,30,Lab,\n"      // 1 fine
				+ "Founders 110,Founders,60,Lecture hall,\n"   // 2 duplicate (catalog refuses)
				+ "Lab D,,20,Lab,\n"                           // 3 missing building (parser refuses)
				+ "Lab E,Innovation,25,Lab,\n");               // 4 fine, still imported after the failures

		assertEquals(2, v.added());
		assertEquals(2, v.needsReview());
		assertEquals(List.of(RowOutcome.ADDED, RowOutcome.NEEDS_REVIEW, RowOutcome.NEEDS_REVIEW, RowOutcome.ADDED),
				v.rows().stream().map(r -> r.outcome()).toList());
		assertEquals("Duplicate room name: 'Founders 110' already exists.", v.rows().get(1).reason());
		assertEquals("Missing required field: building", v.rows().get(2).reason());
		assertEquals(List.of(1, 2, 3, 4), v.rows().stream().map(r -> r.rowNumber()).toList());
		verify(catalog).addRoom(eq("Lab E"), anyString(), anyInt(), any(), anyList());
		assertEquals(4, savedBatch().getRows().size()); // every row is recorded (TR4.6)
	}

	@Test
	@DisplayName("TC-FR3.4b: a subject list adds subjects only, through CatalogService; nothing touches the timetable")
	void subjectList() {
		ImportBatchView v = as(TestUsers.MAYA).importFile("subjects.csv", ImportType.SUBJECT_LIST,
				SUBJECTS + "PSY 330,Social Psychology,3,Lecture hall,(leave blank if none)\n");
		assertEquals(1, v.added());
		verify(catalog).addSubject("PSY 330", "Social Psychology", 3, RoomType.LECTURE_HALL, null, List.of());
		verify(timetable, never()).placeImportedSection(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("UC-12 6a: a duplicate subject code or a department the Scheduler may not use becomes Needs review, not a failed upload")
	void catalogRefusalsBecomeReasons() {
		doThrow(new BusinessRuleException("Duplicate subject code: 'PSY 220' is already in the catalog."))
				.when(catalog).addSubject(eq("PSY 220"), anyString(), anyInt(), any(), any(), anyList());
		doThrow(new AccessDeniedException("You are not authorized to add subjects to the department 'HIST'."))
				.when(catalog).addSubject(eq("HIST 999"), anyString(), anyInt(), any(), any(), anyList());

		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.SUBJECT_LIST, SUBJECTS
				+ "PSY 220,Again,3,Lab,\nHIST 999,History,3,Lab,\nMATH 1,Algebra,3,Lab,\n");

		assertEquals(1, v.added());
		assertTrue(v.rows().get(0).reason().startsWith("Duplicate subject code"));
		assertTrue(v.rows().get(1).reason().contains("not authorized"));
	}

	@Test
	@DisplayName("A database clash or an unexpected error in one row does not stop the rest, and does not leak details")
	void unexpectedProblemsStayInTheirRow() {
		doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint uq_room_name"))
				.when(catalog).addRoom(eq("A"), anyString(), anyInt(), any(), anyList());
		doThrow(new IllegalStateException("boom: internal detail"))
				.when(catalog).addRoom(eq("B"), anyString(), anyInt(), any(), anyList());

		ImportBatchView v = as(TestUsers.MAYA).importFile("r.csv", ImportType.ROOMS,
				ROOMS + "A,X,10,Lab,\nB,X,10,Lab,\nC,X,10,Lab,\n");

		assertEquals(List.of(RowOutcome.NEEDS_REVIEW, RowOutcome.NEEDS_REVIEW, RowOutcome.ADDED),
				v.rows().stream().map(r -> r.outcome()).toList());
		assertTrue(v.rows().get(0).reason().contains("duplicates an existing entry"));
		assertTrue(v.rows().get(1).reason().contains("unexpected problem"));
		assertTrue(v.rows().stream().noneMatch(r -> r.reason() != null && r.reason().contains("internal detail")));
		assertTrue(v.rows().stream().noneMatch(r -> r.reason() != null && r.reason().contains("uq_room_name")));
	}

	@Test
	void fileNameDefaultsAndIsTrimmed() {
		as(TestUsers.MAYA).importFile(null, ImportType.ROOMS, ROOMS);
		assertEquals("upload.csv", savedBatch().getFileName());
	}

	// ---------------- ready-made schedule (FR3.4, FR3.7) ----------------

	@Test
	@DisplayName("A schedule brings in the Core Platform's staff before matching names, so a new instructor is recognized")
	void scheduleRefreshesStaffFirst() {
		InOrder order = inOrder(staffDirectory, users);

		as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "HIST 120,World History,MWF,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n");

		order.verify(staffDirectory).refreshProfiles();
		order.verify(users).findByDisplayNameIgnoreCase("Dr. Patel");
	}

	@Test
	@DisplayName("TC-FR3.4a: HIST 120 and PSY 220 are placed; CS 450L (unrecognized room) is Needs review 'room not recognized'")
	void readyMadeSchedule() {
		ImportBatchView v = as(TestUsers.MAYA).importFile("schedule.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "HIST 120,World History,MWF,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n"
				+ "PSY 220,Cognitive Psychology,TTh,10:00,11:15,Founders 110,Dr. Patel,Fall2026\n"
				+ "CS 450L,Algorithms Lab,F,14:00,16:00,Basement Closet,Dr. Patel,Fall2026\n");

		assertEquals(List.of(RowOutcome.ADDED, RowOutcome.ADDED, RowOutcome.NEEDS_REVIEW),
				v.rows().stream().map(r -> r.outcome()).toList());
		assertEquals("room not recognized", v.rows().get(2).reason());
		verify(timetable).placeImportedSection("Fall2026", "S1", "R2", List.of("U10"),
				List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), LocalTime.of(9, 0), LocalTime.of(9, 50));
		verify(timetable).placeImportedSection("Fall2026", "S2", "R1", List.of("U10"),
				List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), LocalTime.of(10, 0), LocalTime.of(11, 15));
		verify(timetable, times(2)).placeImportedSection(any(), any(), any(), any(), any(), any(), any());
		verify(catalog, never()).addSubject(any(), any(), anyInt(), any(), any(), any()); // nothing new in the catalog
	}

	@Test
	@DisplayName("Room and subject names match ignoring case and spacing ('founders110' finds 'Founders 110')")
	void lookupsIgnoreCaseAndSpacing() {
		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "hist120,World History,M,09:00,09:50,founders110,Dr. Patel,Fall 2026\n");
		assertEquals(1, v.added());
		verify(timetable).placeImportedSection(eq("Fall 2026"), eq("S1"), eq("R1"), anyList(), anyList(), any(), any());
	}

	@Test
	@DisplayName("Co-instructors: every name must resolve; an unknown or ambiguous name is a reason, not a guess")
	void instructorLookups() {
		when(users.findByDisplayNameIgnoreCase("Prof. Kim")).thenReturn(List.of());
		when(users.findByDisplayNameIgnoreCase("Lee")).thenReturn(List.of(
				new AppUserEntity("U20", "Lee", null, null), new AppUserEntity("U21", "Lee", null, null)));

		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "HIST 120,H,M,09:00,09:50,Hale Hall 204,Dr. Patel; Prof. Kim,Fall2026\n"
				+ "HIST 120,H,T,09:00,09:50,Hale Hall 204,Lee,Fall2026\n");

		assertEquals("instructor 'Prof. Kim' not recognized", v.rows().get(0).reason());
		assertTrue(v.rows().get(1).reason().contains("matches more than one person"));
		verify(timetable, never()).placeImportedSection(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void subjectMustAlreadyBeInTheCatalog() {
		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "ART 999,Art,M,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n");
		assertTrue(v.rows().get(0).reason().contains("not in the catalog"), v.rows().get(0).reason());
	}

	@Test
	@DisplayName("UC-12 pre-condition: the timetable's refusal (term unknown / Published) becomes the row's reason")
	void placementRefusalsBecomeReasons() {
		doThrow(new BusinessRuleException("term 'Fall2027' not found"))
				.when(timetable).placeImportedSection(eq("Fall2027"), any(), any(), any(), any(), any(), any());

		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "HIST 120,H,M,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2027\n"
				+ "HIST 120,H,T,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n");

		assertEquals("term 'Fall2027' not found", v.rows().get(0).reason());
		assertEquals(RowOutcome.ADDED, v.rows().get(1).outcome());
	}

	@Test
	@DisplayName("Until Dev A provides SchedulePlacement, schedule rows are Needs review, not a crash")
	void noPlacementAvailableYet() {
		when(placement.getIfAvailable()).thenReturn(null);
		ImportBatchView v = as(TestUsers.MAYA).importFile("s.csv", ImportType.READY_MADE_SCHEDULE, SCHEDULE
				+ "HIST 120,H,M,09:00,09:50,Hale Hall 204,Dr. Patel,Fall2026\n");
		assertEquals(1, v.needsReview());
		assertTrue(v.rows().get(0).reason().contains("not available yet"));
	}
}
