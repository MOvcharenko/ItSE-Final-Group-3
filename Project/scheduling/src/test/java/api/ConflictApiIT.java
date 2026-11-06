package api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import domain.RoomType;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;

/**
 * The conflict API with the real detection service: a room clash placed through the timetable is found, dismissed
 * with a reason, and the dismissal shows in the term's audit log (NFR3.3). Requests commit, so names are unique.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConflictApiIT {

	private static final String SCHEDULER = "U01";
	private static final String BIANCHI = "U02";
	private static final String MONDAY_TEN = """
			[{"pattern":"WEEKLY","day":"MONDAY","start":"10:00","end":"10:50"}]""";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;

	private String termId;
	private String roomId;

	@BeforeEach
	void roomClash() throws Exception {
		termId = createTerm();
		roomId = rooms.save(RoomEntity.create(unique("Room"), "Founders", 200, RoomType.LECTURE_HALL, Set.of())).getId();
		place(termId, BIANCHI);
		place(termId, SCHEDULER);
	}

	@Test
	@DisplayName("TC-NFR3.3: a dismissal is logged with who, when, which conflict and why")
	void dismissalIsLogged() throws Exception {
		String conflictId = roomConflict(termId);

		send(post("/api/conflicts/" + conflictId + "/dismiss"), SCHEDULER, "{\"reason\":\"Shared lecture, agreed\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.resolutionState").value("DISMISSED"));

		send(get("/api/terms/" + termId + "/dismissals"), SCHEDULER, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].conflictId").value(conflictId))
			.andExpect(jsonPath("$[0].conflictType").value("ROOM_DOUBLE_BOOKING"))
			.andExpect(jsonPath("$[0].dismissedById").value(SCHEDULER))
			.andExpect(jsonPath("$[0].dismissedByName").value("Maya Brooks"))
			.andExpect(jsonPath("$[0].reason").value("Shared lecture, agreed"))
			.andExpect(jsonPath("$[0].dismissedAt").exists())
			.andExpect(jsonPath("$[0].explanation").value(org.hamcrest.Matchers.containsString("Room double-booking")));
	}

	@Test
	@DisplayName("TC-NFR3.3: a term's log holds only its own conflicts' dismissals")
	void logIsPerTerm() throws Exception {
		String otherTerm = createTerm();
		place(otherTerm, BIANCHI);
		place(otherTerm, SCHEDULER);
		send(post("/api/conflicts/" + roomConflict(otherTerm) + "/dismiss"), SCHEDULER, "{\"reason\":\"Elsewhere\"}")
			.andExpect(status().isOk());

		send(get("/api/terms/" + termId + "/dismissals"), SCHEDULER, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("The log of a term that does not exist is 404, and only Schedulers may read it")
	void refusals() throws Exception {
		send(get("/api/terms/no-such-term/dismissals"), SCHEDULER, null).andExpect(status().isNotFound());
		send(get("/api/terms/" + termId + "/dismissals"), BIANCHI, null).andExpect(status().isForbidden());
	}

	// ---- Helpers ----

	private String roomConflict(String term) throws Exception {
		String body = send(get("/api/terms/" + term + "/conflicts?type=ROOM_DOUBLE_BOOKING&status=OPEN"), SCHEDULER, null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[0].id");
	}

	private void place(String term, String instructor) throws Exception {
		String subjectId = subjects.save(SubjectEntity.create(unique("C"), "A course", 3, RoomType.LECTURE_HALL, "MATH",
				Set.of())).getId();
		send(post("/api/terms/" + term + "/sections"), SCHEDULER, "{\"subjectId\":\"" + subjectId + "\",\"roomId\":\""
				+ roomId + "\",\"instructorIds\":[\"" + instructor + "\"],\"meetings\":" + MONDAY_TEN + "}")
			.andExpect(status().isCreated());
	}

	private String createTerm() throws Exception {
		String body = send(post("/api/terms"), SCHEDULER, "{\"name\":\"" + unique("Term")
				+ "\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-12-20\"}")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
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
