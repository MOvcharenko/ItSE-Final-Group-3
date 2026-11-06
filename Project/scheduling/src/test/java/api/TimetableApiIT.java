package api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import domain.NoticeEvent;
import domain.ResourceStatus;
import domain.RoomType;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SectionEntity;
import persistence.SectionRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import persistence.TermRepository;
import service.BookingService;
import service.CatalogService;
import service.ConflictService;
import service.ImportService;
import service.NoticeService;

/**
 * Terms and sections through the real API and database. Conflict detection and notices are mocked: here they only
 * need to be called at the right moments. Requests really commit, so every test uses its own unique names.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimetableApiIT {

	private static final String SCHEDULER = "U01";
	private static final String BIANCHI = "U02";
	private static final String STUDENT = "U03";
	private static final String MWF = """
			[{"pattern":"WEEKLY","day":"MONDAY","start":"10:00","end":"10:50"},
			 {"pattern":"WEEKLY","day":"WEDNESDAY","start":"10:00","end":"10:50"},
			 {"pattern":"WEEKLY","day":"FRIDAY","start":"10:00","end":"10:50"}]""";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;
	@Autowired
	AppUserRepository users;
	@Autowired
	SectionRepository sections;
	@Autowired
	TermRepository terms;
	@Autowired
	EquipmentRepository equipment;

	@MockitoBean
	ConflictService conflicts;
	@MockitoBean
	NoticeService notices;
	@MockitoBean
	CatalogService catalog;
	@MockitoBean
	BookingService bookings;
	@MockitoBean
	ImportService imports;

	private String subjectId;
	private String roomId;
	private String otherRoomId;
	private String termId;

	@BeforeEach
	void catalogAndDraftTerm() throws Exception {
		subjectId = subject("MATH");
		roomId = room(ResourceStatus.AVAILABLE);
		otherRoomId = room(ResourceStatus.AVAILABLE);
		termId = createTerm();
	}

	// ---- Terms ----

	@Test
	@DisplayName("TC-FR1.1: a new term starts as a Draft")
	void createTermAsDraft() throws Exception {
		send(post("/api/terms"), SCHEDULER, termJson(unique("Spring"), "2027-01-10", "2027-05-10"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.publishedAt").doesNotExist());
	}

	@Test
	@DisplayName("TC-FR1.1: term input errors are explained")
	void termValidation() throws Exception {
		send(post("/api/terms"), SCHEDULER, termJson(" ", "2027-01-10", "2027-05-10"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.name").value("Term name is required"));
		send(post("/api/terms"), SCHEDULER, termJson(unique("Backwards"), "2027-05-10", "2027-01-10"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("The term must end on or after its start date."));
	}

	@Test
	@DisplayName("TC-FR1.1: term names are unique, ignoring case")
	void duplicateName() throws Exception {
		String name = unique("Taken");
		send(post("/api/terms"), SCHEDULER, termJson(name, "2027-01-10", "2027-05-10")).andExpect(status().isCreated());

		send(post("/api/terms"), SCHEDULER, termJson(name.toUpperCase(), "2027-01-10", "2027-05-10"))
			.andExpect(status().isConflict());
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson(name.toLowerCase())).andExpect(status().isConflict());
	}

	@Test
	@DisplayName("TC-FR1.1: a term can be renamed, including a change of case to its own name")
	void rename() throws Exception {
		String name = unique("Renamed");
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson(name))
			.andExpect(status().isOk()).andExpect(jsonPath("$.name").value(name));
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson(name.toUpperCase())).andExpect(status().isOk());
	}

	@Test
	@DisplayName("TC-FR1.1: a Draft's dates can change, and its sections are re-checked and get a new calendar version")
	void changeDates() throws Exception {
		String sectionId = place();
		int revision = sections.findById(sectionId).orElseThrow().getRevision();

		send(patch("/api/terms/" + termId), SCHEDULER, "{\"startDate\":\"2026-08-25\",\"endDate\":\"2026-12-18\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.startDate").value("2026-08-25"))
			.andExpect(jsonPath("$.endDate").value("2026-12-18"));
		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-19\"}")
			.andExpect(jsonPath("$.startDate").value("2026-08-25"))
			.andExpect(jsonPath("$.endDate").value("2026-12-19"));

		verify(conflicts, times(2)).recheck(termId, ConflictService.Ref.term(termId));
		assertThat(sections.findById(sectionId).orElseThrow().getRevision()).isEqualTo(revision + 2);
	}

	@Test
	@DisplayName("TC-FR1.1: new dates that end before they start are refused, and the name in the same request is not saved")
	void changeDatesBackwards() throws Exception {
		String name = unique("Kept");
		send(patch("/api/terms/" + termId), SCHEDULER,
				"{\"name\":\"" + name + "\",\"startDate\":\"2026-12-01\",\"endDate\":\"2026-09-01\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("The term must end on or after its start date."));

		send(get("/api/terms/" + termId), SCHEDULER, null)
			.andExpect(jsonPath("$.name").value(not(name)))
			.andExpect(jsonPath("$.startDate").value("2026-09-01"));
	}

	@Test
	@DisplayName("TC-FR1.1: dates that would leave an exam outside the term are refused, naming the section")
	void changeDatesStrandsExam() throws Exception {
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement("""
				[{"pattern":"WEEKLY","day":"MONDAY","start":"10:00","end":"10:50"},
				 {"pattern":"EXAM","date":"2026-12-15","start":"09:00","end":"11:00"}]"""))
			.andExpect(status().isCreated());

		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-10\"}")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(containsString("meets on 2026-12-15, which would be outside the new dates")));
		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-15\"}").andExpect(status().isOk());
	}

	@Test
	@DisplayName("TC-FR1.1: a published term's dates are locked, but it can still be renamed")
	void publishedDatesLocked() throws Exception {
		placeAndPublish();

		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-18\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("A published term's dates cannot be changed."));
		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-20\"}").andExpect(status().isOk());
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson(unique("Live"))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("TC-FR1.1: moving dates needs authority for every section in the term")
	void changeDatesNeedsAllDepartments() throws Exception {
		englishSection();

		send(patch("/api/terms/" + termId), SCHEDULER, "{\"endDate\":\"2026-12-18\"}").andExpect(status().isForbidden());
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson(unique("Still"))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("TC-FR1.1: a blank name in an update is refused")
	void blankNameOnUpdate() throws Exception {
		send(patch("/api/terms/" + termId), SCHEDULER, nameJson("  "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.name").value("Term name is required"));
	}

	@Test
	@DisplayName("TC-FR1.1: copying a published term makes a new Draft with its sections and leaves the source alone")
	void copy() throws Exception {
		String sectionId = place();
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		String body = send(post("/api/terms/" + termId + "/copy"), SCHEDULER, nameJson(unique("Copy")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andReturn().getResponse().getContentAsString();
		String copyId = JsonPath.read(body, "$.id");

		send(get("/api/terms/" + copyId + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(org.hamcrest.Matchers.not(sectionId)))
			.andExpect(jsonPath("$[0].roomId").value(roomId))
			.andExpect(jsonPath("$[0].meetings.length()").value(3))
			.andExpect(jsonPath("$[0].instructors[0].id").value(BIANCHI));
		send(get("/api/terms/" + termId), SCHEDULER, null).andExpect(jsonPath("$.status").value("PUBLISHED"));
		send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].id").value(sectionId));
		verify(conflicts).recheck(eq(copyId), any());
	}

	@Test
	@DisplayName("TC-FR1.1: deleting a Draft removes its sections; a published term cannot be deleted")
	void deleteTerm() throws Exception {
		String sectionId = place();
		send(delete("/api/terms/" + termId), SCHEDULER, null).andExpect(status().isNoContent());
		send(get("/api/terms/" + termId), SCHEDULER, null).andExpect(status().isNotFound());
		assertThat(sections.existsById(sectionId)).isFalse();

		String published = createTerm();
		send(post("/api/terms/" + published + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		send(delete("/api/terms/" + published), SCHEDULER, null).andExpect(status().isConflict());
	}

	@Test
	@DisplayName("TC-TR3.2: a student only sees published terms; an instructor also sees drafts")
	void termVisibility() throws Exception {
		send(get("/api/terms/" + termId), STUDENT, null).andExpect(status().isNotFound());
		assertThat(listTermIds(STUDENT)).doesNotContain(termId);
		assertThat(listTermIds(BIANCHI)).contains(termId);

		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		send(get("/api/terms/" + termId), STUDENT, null).andExpect(status().isOk());
		assertThat(listTermIds(STUDENT)).contains(termId);
	}

	// ---- Publishing ----

	@Test
	@DisplayName("TC-FR4.7: open High conflicts need confirmation, then publishing goes ahead")
	void publishWithHighConflicts() throws Exception {
		when(conflicts.countOpenHigh(termId)).thenReturn(2);

		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("2 open High-severity conflicts")));
		send(get("/api/terms/" + termId), SCHEDULER, null).andExpect(jsonPath("$.status").value("DRAFT"));

		send(post("/api/terms/" + termId + "/publish?confirm=true"), SCHEDULER, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED"))
			.andExpect(jsonPath("$.publishedAt").exists());
	}

	@Test
	@DisplayName("TC-FR4.7: with no High conflicts, publishing needs no confirmation and happens only once")
	void publishClean() throws Exception {
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isConflict());
	}

	// ---- Placing ----

	@Test
	@DisplayName("TC-FR1.2: placing a section saves its meetings and instructor, and checks conflicts")
	void placeWeekly() throws Exception {
		String body = send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(MWF))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sectionNumber").value("01"))
			.andExpect(jsonPath("$.roomId").value(roomId))
			.andExpect(jsonPath("$.meetings.length()").value(3))
			.andExpect(jsonPath("$.instructors[0].name").value("L. Bianchi"))
			.andReturn().getResponse().getContentAsString();

		verify(conflicts).recheck(termId, ConflictService.Ref.section(JsonPath.read(body, "$.id")));
		verify(notices, never()).notifyAffected(anyString(), any());
	}

	@Test
	@DisplayName("TC-FR1.2: one-time meetings and exams are placed by date")
	void placeExam() throws Exception {
		String meetings = """
				[{"pattern":"EXAM","date":"2026-12-15","start":"09:00","end":"11:00"},
				 {"pattern":"ONE_TIME","date":"2026-10-01","start":"13:00","end":"14:00"}]""";
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(meetings))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.meetings[?(@.pattern == 'EXAM')].date").value("2026-12-15"));
	}

	@ParameterizedTest(name = "{1}")
	@CsvSource(delimiter = '|', value = {
			"[{\"pattern\":\"WEEKLY\",\"day\":\"MONDAY\",\"start\":\"11:00\",\"end\":\"10:00\"}] | Start time must be before end time.",
			"[{\"pattern\":\"WEEKLY\",\"day\":\"MONDAY\",\"start\":\"10:00\",\"end\":\"10:00\"}] | Start time must be before end time.",
			"[{\"pattern\":\"WEEKLY\",\"start\":\"10:00\",\"end\":\"11:00\"}] | A weekly meeting needs a day of the week and no date.",
			"[{\"pattern\":\"EXAM\",\"start\":\"10:00\",\"end\":\"11:00\"}] | A one-time meeting or exam needs a date and no day of the week.",
			"[{\"day\":\"MONDAY\",\"start\":\"10:00\",\"end\":\"11:00\"}] | Mark each meeting as weekly, one-time or exam.",
			"[{\"pattern\":\"EXAM\",\"date\":\"2027-03-01\",\"start\":\"10:00\",\"end\":\"11:00\"}] | The date 2027-03-01 is outside this term.",
			"[] | A section needs at least one meeting time." })
	@DisplayName("TC-FR1.2: a bad meeting is refused with a plain explanation and nothing is saved")
	void badMeetings(String meetings, String message) throws Exception {
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(meetings))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(message));
		verify(conflicts, never()).recheck(anyString(), any());
	}

	@Test
	@DisplayName("TC-FR1.2: the room and instructors must be real, and the room in service")
	void badAssignment() throws Exception {
		String maintenance = room(ResourceStatus.MAINTENANCE);
		expectRefused(json(subjectId, null, instructors(BIANCHI), MWF), "Choose a room for this section.");
		expectRefused(json(subjectId, "nope", instructors(BIANCHI), MWF), "That room is not in the catalog.");
		expectRefused(json(subjectId, roomId, "[]", MWF), "A section needs at least one instructor.");
		expectRefused(json(subjectId, roomId, instructors("nobody"), MWF),
				"One of the chosen instructors was not recognized.");
		expectRefused(json("nope", roomId, instructors(BIANCHI), MWF), "That subject is not in the catalog.");
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, json(subjectId, maintenance, instructors(BIANCHI), MWF))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	@DisplayName("TC-FR1.2: equipment needs are optional, saved, copied, and must be real")
	void equipmentNeeds() throws Exception {
		String projector = equipment.save(EquipmentEntity.create(unique("Projector"), "Projector", 3, null)).getId();
		String needs = "[{\"equipmentId\":\"" + projector + "\",\"units\":2}]";
		String withNeeds = placement(MWF).replaceFirst("}$", ",\"equipment\":" + needs + "}");

		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, withNeeds)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.equipment[0].equipmentId").value(projector))
			.andExpect(jsonPath("$.equipment[0].units").value(2));

		String copy = send(post("/api/terms/" + termId + "/copy"), SCHEDULER, nameJson(unique("Copy")))
			.andReturn().getResponse().getContentAsString();
		send(get("/api/terms/" + JsonPath.read(copy, "$.id") + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].equipment[0].units").value(2));

		String otherTerm = createTerm();
		send(post("/api/terms/" + otherTerm + "/sections"), SCHEDULER, withNeeds.replace(projector, "nope"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("That equipment item is not in the catalog."));
		send(post("/api/terms/" + otherTerm + "/sections"), SCHEDULER, withNeeds.replace("\"units\":2", "\"units\":0"))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	@DisplayName("The same subject and section number cannot be placed twice in a term")
	void duplicateSection() throws Exception {
		place();
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(MWF)).andExpect(status().isConflict());
	}

	@Test
	@DisplayName("TC-TR3.4: a scheduler cannot place a section outside their departments")
	void otherDepartment() throws Exception {
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER,
				json(subject("ENG"), roomId, instructors(BIANCHI), MWF))
			.andExpect(status().isForbidden());
		verify(conflicts, never()).recheck(anyString(), any());
	}

	@Test
	@DisplayName("Imported subjects with no department yet can be placed")
	void subjectWithoutDepartment() throws Exception {
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER,
				json(subject(null), roomId, instructors(BIANCHI), MWF))
			.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("UC-02: a failed conflict check rolls the whole placement back")
	void oneTransaction() throws Exception {
		doThrow(new IllegalStateException("detection failed")).when(conflicts).recheck(eq(termId), any());

		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(MWF))
			.andExpect(status().isInternalServerError());

		send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null).andExpect(jsonPath("$.length()").value(0));
	}

	// ---- Editing ----

	@Test
	@DisplayName("TC-FR1.6a: a co-instructor can be added, and the section keeps both")
	void coInstructor() throws Exception {
		String sectionId = place();
		String coInstructor = instructor();

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER,
				"{\"instructorIds\":" + instructors(BIANCHI, coInstructor) + "}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instructors.length()").value(2))
			.andExpect(jsonPath("$.roomId").value(roomId));
	}

	@Test
	@DisplayName("TC-FR1.6b: adding a co-instructor re-checks conflicts for that section")
	void coInstructorRecheck() throws Exception {
		String sectionId = place();

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER,
				"{\"instructorIds\":" + instructors(BIANCHI, instructor()) + "}")
			.andExpect(status().isOk());

		verify(conflicts, times(2)).recheck(termId, ConflictService.Ref.section(sectionId));
	}

	@Test
	@DisplayName("TC-FR1.6a: a section cannot be left with no instructor")
	void noInstructorLeft() throws Exception {
		send(patch("/api/sections/" + place() + "/assignment"), SCHEDULER, "{\"instructorIds\":[]}")
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	@DisplayName("TC-FR1.5: editing a Draft section notifies nobody")
	void draftEditIsSilent() throws Exception {
		send(patch("/api/sections/" + place() + "/assignment"), SCHEDULER, "{\"roomId\":\"" + otherRoomId + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.roomId").value(otherRoomId));
		verify(notices, never()).notifyAffected(anyString(), any());
	}

	@Test
	@DisplayName("TC-FR1.5: a room change in a Published term is allowed and notifies those affected")
	void publishedRoomChange() throws Exception {
		String sectionId = placeAndPublish();

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER, "{\"roomId\":\"" + otherRoomId + "\"}")
			.andExpect(status().isOk());

		verify(notices).notifyAffected(sectionId, NoticeEvent.SECTION_CHANGED);
	}

	@Test
	@DisplayName("TC-FR1.5: an instructor change in a Published term sends the reassignment notice")
	void publishedInstructorChange() throws Exception {
		String sectionId = placeAndPublish();

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER,
				"{\"instructorIds\":" + instructors(instructor()) + "}")
			.andExpect(status().isOk());

		verify(notices).notifyAffected(sectionId, NoticeEvent.INSTRUCTOR_REASSIGNED);
		verify(notices).notifyRemovedInstructors(sectionId, Set.of(BIANCHI));
	}

	@Test
	@DisplayName("TC-FR1.5: a room and instructor change together sends one notice to those affected")
	void publishedRoomAndInstructorChange() throws Exception {
		String sectionId = placeAndPublish();

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER, "{\"roomId\":\"" + otherRoomId
				+ "\",\"instructorIds\":" + instructors(instructor()) + "}")
			.andExpect(status().isOk());

		verify(notices).notifyAffected(sectionId, NoticeEvent.SECTION_CHANGED);
		verify(notices, never()).notifyAffected(sectionId, NoticeEvent.INSTRUCTOR_REASSIGNED);
		verify(notices).notifyRemovedInstructors(sectionId, Set.of(BIANCHI));
	}

	@Test
	@DisplayName("TC-FR1.5: a time change through PUT replaces the meetings and notifies")
	void publishedTimeChange() throws Exception {
		String sectionId = placeAndPublish();
		String tuesday = "[{\"pattern\":\"WEEKLY\",\"day\":\"TUESDAY\",\"start\":\"14:00\",\"end\":\"15:15\"}]";

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, placement(tuesday))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.meetings.length()").value(1))
			.andExpect(jsonPath("$.meetings[0].day").value("TUESDAY"));

		verify(notices).notifyAffected(sectionId, NoticeEvent.SECTION_CHANGED);
		send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].meetings.length()").value(1));
	}

	@Test
	@DisplayName("An equipment-only change in a Published term saves and re-checks conflicts, but notifies nobody")
	void publishedEquipmentChange() throws Exception {
		String sectionId = placeAndPublish();
		String projector = equipment.save(EquipmentEntity.create(unique("Projector"), "Projector", 3, null)).getId();
		String withEquipment = placement(MWF).replaceFirst("}$",
				",\"equipment\":[{\"equipmentId\":\"" + projector + "\",\"units\":1}]}");

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, withEquipment)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.equipment[0].equipmentId").value(projector));

		verify(conflicts, times(2)).recheck(termId, ConflictService.Ref.section(sectionId));
		verify(notices, never()).notifyAffected(anyString(), any());
	}

	@Test
	@DisplayName("A room change that also changes equipment still notifies")
	void publishedRoomAndEquipmentChange() throws Exception {
		String sectionId = placeAndPublish();
		String projector = equipment.save(EquipmentEntity.create(unique("Projector"), "Projector", 3, null)).getId();
		String body = json(subjectId, otherRoomId, instructors(BIANCHI), MWF).replaceFirst("}$",
				",\"equipment\":[{\"equipmentId\":\"" + projector + "\",\"units\":1}]}");

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, body).andExpect(status().isOk());

		verify(notices).notifyAffected(sectionId, NoticeEvent.SECTION_CHANGED);
	}

	@Test
	@DisplayName("An edit that changes nothing does not re-check or notify")
	void noOpEdit() throws Exception {
		String sectionId = placeAndPublish();

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, placement(MWF))
			.andExpect(status().isOk());

		verify(conflicts, times(1)).recheck(anyString(), any());
		verify(notices, never()).notifyAffected(anyString(), any());
	}

	@Test
	@DisplayName("A section already in a room now under maintenance can still have its time changed")
	void editInMaintenanceRoom() throws Exception {
		String sectionId = place();
		RoomEntity room = rooms.findById(roomId).orElseThrow();
		room.setStatus(ResourceStatus.MAINTENANCE);
		rooms.save(room);

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER,
				placement("[{\"pattern\":\"WEEKLY\",\"day\":\"TUESDAY\",\"start\":\"14:00\",\"end\":\"15:15\"}]"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("A section's subject cannot change, and it must belong to the term in the path")
	void putGuards() throws Exception {
		String sectionId = place();
		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER,
				json(subject("MATH"), roomId, instructors(BIANCHI), MWF))
			.andExpect(status().isUnprocessableContent());
		send(put("/api/terms/" + createTerm() + "/sections/" + sectionId), SCHEDULER, placement(MWF))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("TC-TR3.4: a scheduler cannot edit a section outside their departments")
	void editOtherDepartment() throws Exception {
		String english = englishSection();

		send(patch("/api/sections/" + english + "/assignment"), SCHEDULER, "{\"roomId\":\"" + roomId + "\"}")
			.andExpect(status().isForbidden());
		verify(conflicts, never()).recheck(anyString(), any());
	}

	@Test
	@DisplayName("TC-TR3.4: a term holding another department's section cannot be deleted or copied by this scheduler")
	void wholeTermOtherDepartment() throws Exception {
		String english = englishSection();

		send(delete("/api/terms/" + termId), SCHEDULER, null).andExpect(status().isForbidden());
		send(post("/api/terms/" + termId + "/copy"), SCHEDULER, nameJson(unique("Copy")))
			.andExpect(status().isForbidden());

		assertThat(sections.existsById(english)).isTrue();
	}

	@Test
	@DisplayName("Two simultaneous edits of a section never mix: it ends with one edit's meetings")
	void concurrentEdits() throws Exception {
		String sectionId = place();
		String url = "/api/terms/" + termId + "/sections/" + sectionId;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				CountDownLatch gate = new CountDownLatch(1);
				List<Future<Integer>> edits = new ArrayList<>();
				for (String day : List.of("TUESDAY", "THURSDAY")) {
					String body = placement("[{\"pattern\":\"WEEKLY\",\"day\":\"" + day + "\",\"start\":\"" + (10 + round)
							+ ":00\",\"end\":\"" + (10 + round) + ":50\"}]");
					edits.add(pool.submit(() -> {
						gate.await();
						return send(put(url), SCHEDULER, body).andReturn().getResponse().getStatus();
					}));
				}
				gate.countDown();
				for (Future<Integer> edit : edits) {
					assertThat(edit.get()).isEqualTo(200);
				}
				send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null)
					.andExpect(jsonPath("$[0].meetings.length()").value(1));
			}
		} finally {
			pool.shutdown();
		}
	}

	@Test
	@DisplayName("Removing a Draft section notifies nobody and re-checks conflicts")
	void removeDraftSection() throws Exception {
		String sectionId = place();

		send(delete("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, null)
			.andExpect(status().isNoContent());

		assertThat(sections.existsById(sectionId)).isFalse();
		verify(conflicts, times(2)).recheck(termId, ConflictService.Ref.section(sectionId));
		verify(notices, never()).notifyAffected(anyString(), any());
	}

	@Test
	@DisplayName("Removing a section from a Published term sends a cancellation notice")
	void removePublishedSection() throws Exception {
		String sectionId = placeAndPublish();

		send(delete("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, null)
			.andExpect(status().isNoContent());

		verify(notices).notifyAffected(sectionId, NoticeEvent.SECTION_CANCELLED);
	}

	@Test
	@DisplayName("A section can only be removed through its own term, and within the caller's departments")
	void removeGuards() throws Exception {
		String sectionId = place();
		send(delete("/api/terms/" + createTerm() + "/sections/" + sectionId), SCHEDULER, null)
			.andExpect(status().isNotFound());
		String english = englishSection();
		send(delete("/api/terms/" + termId + "/sections/" + english), SCHEDULER, null)
			.andExpect(status().isForbidden());

		assertThat(sections.existsById(sectionId)).isTrue();
		assertThat(sections.existsById(english)).isTrue();
	}

	@Test
	@DisplayName("TC-FR1.5: adding a section to a Published term notifies those affected")
	void placeInPublishedTerm() throws Exception {
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		String body = send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(MWF))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		verify(notices).notifyAffected(JsonPath.read(body, "$.id"), NoticeEvent.SECTION_CHANGED);
	}

	@Test
	@DisplayName("TC-FR1.1: a copy leaves out a room that is now out of service, and keeps the other details")
	void copyWithRoomOutOfService() throws Exception {
		place();
		RoomEntity room = rooms.findById(roomId).orElseThrow();
		room.setStatus(ResourceStatus.MAINTENANCE);
		rooms.save(room);

		String copy = send(post("/api/terms/" + termId + "/copy"), SCHEDULER, nameJson(unique("Copy")))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		send(get("/api/terms/" + JsonPath.read(copy, "$.id") + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].roomId").doesNotExist())
			.andExpect(jsonPath("$[0].meetings.length()").value(3));
	}

	@Test
	@DisplayName("TC-FR1.2: null list entries and bad section numbers are refused with an explanation")
	void malformedPlacement() throws Exception {
		expectRefused(placement("[null]"), "Mark each meeting as weekly, one-time or exam.");
		expectRefused(placement(MWF).replaceFirst("}$", ",\"equipment\":[null]}"),
				"That equipment item is not in the catalog.");
		expectRefused(placement(MWF).replace(instructors(BIANCHI), "[null]"),
				"One of the chosen instructors was not recognized.");
		String numberError = "A section number needs 1 to 10 characters.";
		expectRefused(placement(MWF).replaceFirst("}$", ",\"sectionNumber\":\"  \"}"), numberError);
		expectRefused(placement(MWF).replaceFirst("}$", ",\"sectionNumber\":\"12345678901\"}"), numberError);
	}

	// ---- Helpers ----

	/** Saved directly, because this scheduler is not allowed to place it through the API. */
	private String englishSection() {
		return sections.save(SectionEntity.create(terms.findById(termId).orElseThrow(),
				subjects.findById(subject("ENG")).orElseThrow(), "01")).getId();
	}

	private String placeAndPublish() throws Exception {
		String sectionId = place();
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		return sectionId;
	}

	private String place() throws Exception {
		String body = send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(MWF))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String createTerm() throws Exception {
		String body = send(post("/api/terms"), SCHEDULER, termJson(unique("Fall"), "2026-09-01", "2026-12-20"))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private List<String> listTermIds(String user) throws Exception {
		String body = send(get("/api/terms"), user, null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	private void expectRefused(String body, String message) throws Exception {
		send(post("/api/terms/" + termId + "/sections"), SCHEDULER, body)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value(message));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	@Test
	@DisplayName("A Scheduler can list who may be chosen as an instructor")
	void listsInstructors() throws Exception {
		mvc.perform(get("/api/instructors").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(SCHEDULER)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + BIANCHI + "')].name").value("L. Bianchi"));
	}

	private String tokenFor(String userId) throws Exception {
		String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"" + userId + "\"}"))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private String placement(String meetings) {
		return json(subjectId, roomId, instructors(BIANCHI), meetings);
	}

	private static String json(String subject, String room, String instructors, String meetings) {
		return "{\"subjectId\":\"" + subject + "\"," + (room == null ? "" : "\"roomId\":\"" + room + "\",")
				+ "\"instructorIds\":" + instructors + ",\"meetings\":" + meetings + "}";
	}

	private static String instructors(String... ids) {
		return "[\"" + String.join("\",\"", ids) + "\"]";
	}

	private static String termJson(String name, String start, String end) {
		return "{\"name\":\"" + name + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}";
	}

	private static String nameJson(String name) {
		return "{\"name\":\"" + name + "\"}";
	}

	private static String unique(String prefix) {
		return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	private String subject(String department) {
		return subjects.save(SubjectEntity.create(unique("C"), "A course", 3, RoomType.LECTURE_HALL, department, Set.of()))
			.getId();
	}

	private String room(ResourceStatus status) {
		RoomEntity room = RoomEntity.create(unique("Room"), "Founders", 40, RoomType.LECTURE_HALL, Set.of());
		room.setStatus(status);
		return rooms.save(room).getId();
	}

	private String instructor() {
		String id = unique("I").replace(" ", "-");
		users.save(new AppUserEntity(id, "Co Instructor", null, "MATH"));
		return id;
	}

}
