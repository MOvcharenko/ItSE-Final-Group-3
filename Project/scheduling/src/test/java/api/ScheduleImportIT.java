package api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import domain.ResourceStatus;
import domain.RoomType;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import service.ConflictService;

/**
 * TC-FR3.4 and TC-FR3.7: a ready-made schedule file goes through the real import, timetable and database. Only conflict
 * detection is mocked, because here it only needs to be called. Requests really commit, so names are unique.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleImportIT {

	private static final String SCHEDULER = "U01";
	private static final String HEADER = "subject_code,title,days,start_time,end_time,room,instructor,term\n";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;
	@Autowired
	AppUserRepository users;

	@MockitoBean
	ConflictService conflicts;

	private String course;
	private String room;
	private String instructor;
	private String termName;
	private String termId;

	@BeforeEach
	void catalogAndDraftTerm() throws Exception {
		course = subject("MATH");
		room = room(ResourceStatus.AVAILABLE);
		instructor = unique("Dr");
		users.save(new AppUserEntity(unique("I").replace(" ", "-"), instructor, null, "MATH"));
		termName = unique("Fall");
		termId = term(termName);
	}

	@Test
	@DisplayName("TC-FR3.4: an MWF row becomes one Draft section with three weekly meetings")
	void placesARow() throws Exception {
		upload(row(course, "MWF", "09:00", "09:50", room, termName))
			.andExpect(jsonPath("$.added").value(1))
			.andExpect(jsonPath("$.needsReview").value(0));

		String sections = sectionsJson();
		assertThat(JsonPath.<List<String>>read(sections, "$[*].courseCode")).containsExactly(course);
		assertThat(JsonPath.<String>read(sections, "$[0].sectionNumber")).isEqualTo("01");
		assertThat(JsonPath.<String>read(sections, "$[0].roomName")).isEqualTo(room);
		assertThat(JsonPath.<List<String>>read(sections, "$[0].instructors[*].name")).containsExactly(instructor);
		assertThat(JsonPath.<List<String>>read(sections, "$[0].meetings[*].day"))
			.containsExactlyInAnyOrder("MONDAY", "WEDNESDAY", "FRIDAY");
		assertThat(JsonPath.<List<String>>read(sections, "$[0].meetings[*].pattern")).containsOnly("WEEKLY");
	}

	@Test
	@DisplayName("TC-FR3.7: each placed row runs conflict detection straight away")
	void rechecksConflicts() throws Exception {
		upload(row(course, "M", "09:00", "09:50", room, termName)).andExpect(jsonPath("$.added").value(1));

		String sectionId = JsonPath.read(sectionsJson(), "$[0].id");
		verify(conflicts).recheck(eq(termId), eq(ConflictService.Ref.section(sectionId)));
	}

	@Test
	@DisplayName("TC-FR3.4: the term name matches ignoring case and spaces")
	void termNameIgnoresCaseAndSpaces() throws Exception {
		upload(row(course, "M", "09:00", "09:50", room, termName.replace(" ", "").toUpperCase()))
			.andExpect(jsonPath("$.added").value(1));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].courseCode")).containsExactly(course);
	}

	@Test
	@DisplayName("TC-FR3.4: two rows for one course become sections 01 and 02")
	void numbersSectionsInOrder() throws Exception {
		upload(row(course, "M", "09:00", "09:50", room, termName) + row(course, "T", "09:00", "09:50", room, termName))
			.andExpect(jsonPath("$.added").value(2));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].sectionNumber")).containsExactly("01", "02");
	}

	@Test
	@DisplayName("TC-FR3.4: uploading the same file twice adds nothing the second time")
	void sameFileTwice() throws Exception {
		String file = row(course, "MW", "09:00", "09:50", room, termName);
		upload(file).andExpect(jsonPath("$.added").value(1));

		upload(file)
			.andExpect(jsonPath("$.added").value(0))
			.andExpect(jsonPath("$.rows[0].outcome").value("NEEDS_REVIEW"))
			.andExpect(jsonPath("$.rows[0].reason").value("this section is already in term '" + termName + "'"));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].id")).hasSize(1);
	}

	@Test
	@DisplayName("UC-12: an unknown term is that row's reason, and the good rows stay")
	void unknownTerm() throws Exception {
		upload(row(course, "M", "09:00", "09:50", room, "No Such Term") + row(course, "T", "09:00", "09:50", room, termName))
			.andExpect(jsonPath("$.added").value(1))
			.andExpect(jsonPath("$.rows[0].outcome").value("NEEDS_REVIEW"))
			.andExpect(jsonPath("$.rows[0].reason").value("term 'No Such Term' not found"))
			.andExpect(jsonPath("$.rows[1].outcome").value("ADDED"));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].id")).hasSize(1);
	}

	@Test
	@DisplayName("UC-12: a Published term takes no imported sections")
	void publishedTerm() throws Exception {
		mvc.perform(post("/api/terms/" + termId + "/publish")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(SCHEDULER)))
			.andExpect(status().isOk());

		upload(row(course, "M", "09:00", "09:50", room, termName))
			.andExpect(jsonPath("$.added").value(0))
			.andExpect(jsonPath("$.rows[0].reason").value("term '" + termName + "' is already Published"));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].id")).isEmpty();
	}

	@Test
	@DisplayName("UC-12: two terms that differ only by spaces are not guessed between")
	void ambiguousTermName() throws Exception {
		String name = "Amb" + UUID.randomUUID().toString().substring(0, 8);
		term(name + " 1");
		term(name + "1");

		upload(row(course, "M", "09:00", "09:50", room, name + "1"))
			.andExpect(jsonPath("$.added").value(0))
			.andExpect(jsonPath("$.rows[0].reason").value("term name '" + name + "1' matches more than one term"));
	}

	@Test
	@DisplayName("TR3.4: a subject outside the Scheduler's departments is refused for that row alone")
	void otherDepartment() throws Exception {
		String biology = subject("BIO");

		upload(row(biology, "M", "09:00", "09:50", room, termName) + row(course, "T", "09:00", "09:50", room, termName))
			.andExpect(jsonPath("$.added").value(1))
			.andExpect(jsonPath("$.rows[0].reason").value("Your account is not authorized for the BIO department."));

		assertThat(JsonPath.<List<String>>read(sectionsJson(), "$[*].courseCode")).containsExactly(course);
	}

	@Test
	@DisplayName("FR2.5: a room under maintenance is refused with the timetable's own wording")
	void roomInMaintenance() throws Exception {
		String closed = room(ResourceStatus.MAINTENANCE);

		upload(row(course, "M", "09:00", "09:50", closed, termName))
			.andExpect(jsonPath("$.added").value(0))
			.andExpect(jsonPath("$.rows[0].reason").value(closed + " is not available for scheduling right now."));
	}

	private String row(String code, String days, String start, String end, String roomName, String term) {
		return code + ",A course," + days + "," + start + "," + end + "," + roomName + "," + instructor + "," + term + "\n";
	}

	private ResultActions upload(String rows) throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "schedule.csv", "text/csv",
				(HEADER + rows).getBytes(StandardCharsets.UTF_8));
		return mvc.perform(multipart("/api/import/ready-made-schedule").file(file)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(SCHEDULER)))
			.andExpect(status().isOk());
	}

	private String sectionsJson() throws Exception {
		return mvc.perform(get("/api/terms/" + termId + "/sections")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(SCHEDULER)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private String term(String name) throws Exception {
		String body = mvc.perform(post("/api/terms")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(SCHEDULER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\",\"startDate\":\"2061-09-01\",\"endDate\":\"2061-12-20\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String subject(String department) {
		String code = unique("C");
		subjects.save(SubjectEntity.create(code, "A course", 3, RoomType.LECTURE_HALL, department, Set.of()));
		return code;
	}

	private String room(ResourceStatus status) {
		String name = unique("Room");
		RoomEntity entity = RoomEntity.create(name, "Founders", 40, RoomType.LECTURE_HALL, Set.of());
		entity.setStatus(status);
		rooms.save(entity);
		return name;
	}

	private String tokenFor(String userId) throws Exception {
		String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"" + userId + "\"}"))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	private static String unique(String prefix) {
		return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
	}

}
