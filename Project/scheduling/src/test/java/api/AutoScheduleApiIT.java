package api;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import domain.RoomType;
import net.minidev.json.JSONValue;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import support.Td1;
import support.Td1Loader;

/**
 * Integration tests for SAT/MaxSAT Auto-Schedule REST API (Dev B).
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AutoScheduleApiIT {

	private static final String TERM_ID = Td1.TERM_FALL_2026;
	private static final String SCHEDULER_ID = Td1.MAYA_BROOKS;
	private static final String INSTRUCTOR_ID = Td1.L_BIANCHI;
	private static final String STUDENT_ID = Td1.JORDAN_ELLIS;

	@Autowired
	MockMvc mvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	SubjectRepository subjects;

	@BeforeEach
	void loadFixture() throws Exception {
		Connection c = DataSourceUtils.getConnection(dataSource);
		Td1Loader.resetAndLoad(c);
	}

	@Test
	@DisplayName("Auto-schedule rebuild of TD-1 Fall 2026 eliminates all 3 seeded conflicts")
	void previewAndApplyRebuildEliminatesTd1Conflicts() throws Exception {
		String previewJson = send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"),
				SCHEDULER_ID, "{\"keepExistingFixed\":false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.solvable").value(true))
				.andExpect(jsonPath("$.placements", hasSize(7)))
				.andReturn().getResponse().getContentAsString();

		List<Map<String, Object>> placements = JsonPath.read(previewJson, "$.placements");
		String applyBody = JSONValue.toJSONString(Map.of(
				"replaceExisting", true,
				"placements", placements.stream().map(p -> Map.of(
						"subjectId", p.get("subjectId"),
						"sectionNumber", p.get("sectionNumber"),
						"roomId", p.get("roomId"),
						"instructorIds", List.of(p.get("instructorId")),
						"meetings", p.get("meetings"),
						"equipment", p.get("equipment")
				)).toList()));

		send(post("/api/terms/" + TERM_ID + "/auto-schedule/apply"), SCHEDULER_ID, applyBody)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(7)));

		// Verify 0 OPEN conflicts exist after applying the SAT solution
		send(get("/api/terms/" + TERM_ID + "/conflicts?status=OPEN"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	@DisplayName("Keep-existing mode schedules only a newly added unscheduled subject around fixed sections")
	void previewKeepExistingSchedulesNewSubjectAroundFixed() throws Exception {
		SubjectEntity extra = subjects.save(SubjectEntity.create("CS 499", "Capstone", 3, RoomType.LAB, "CS", Set.of()));

		send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"),
				SCHEDULER_ID, "{\"keepExistingFixed\":true}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.solvable").value(true))
				.andExpect(jsonPath("$.placements", hasSize(1)))
				.andExpect(jsonPath("$.placements[0].subjectId").value(extra.getId()));
	}

	@Test
	@DisplayName("Try Another Option passes excludedSolutions and returns a distinct conflict-free option")
	void tryAnotherOptionReturnsDistinctSchedule() throws Exception {
		String opt1Json = send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"),
				SCHEDULER_ID, "{\"keepExistingFixed\":false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.solvable").value(true))
				.andReturn().getResponse().getContentAsString();

		List<Map<String, Object>> opt1Placements = JsonPath.read(opt1Json, "$.placements");
		List<Map<String, Object>> choiceKeys = opt1Placements.stream().map(p -> Map.<String, Object>of(
				"subjectId", p.get("subjectId"),
				"sectionNumber", p.get("sectionNumber"),
				"roomId", p.get("roomId"),
				"instructorId", p.get("instructorId"),
				"patternId", p.get("patternId"))).toList();

		String req2Body = JSONValue.toJSONString(Map.of(
				"keepExistingFixed", false,
				"excludedSolutions", List.of(choiceKeys)));

		String opt2Json = send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"), SCHEDULER_ID, req2Body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.solvable").value(true))
				.andReturn().getResponse().getContentAsString();

		List<String> patterns1 = JsonPath.read(opt1Json, "$.placements[*].patternId");
		List<String> patterns2 = JsonPath.read(opt2Json, "$.placements[*].patternId");
		assertNotEquals(patterns1, patterns2);
		assertEquals(7, patterns2.size());
	}

	@Test
	@DisplayName("Rejects auto-schedule preview and apply on PUBLISHED terms with 409 Conflict")
	void rejectsPublishedTermWith409() throws Exception {
		send(post("/api/terms/" + TERM_ID + "/publish?confirm=true"), SCHEDULER_ID, null)
				.andExpect(status().isOk());

		send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"), SCHEDULER_ID, "{}")
				.andExpect(status().isConflict());

		String validApply = "{\"placements\":[{\"subjectId\":\"SUB-CS340\",\"roomId\":\"" + Td1.INNOVATION_LAB_B
				+ "\",\"instructorIds\":[\"U04\"],\"meetings\":[{\"pattern\":\"WEEKLY\",\"day\":\"TUESDAY\",\"start\":\"09:00\",\"end\":\"09:50\"}]}]}";
		send(post("/api/terms/" + TERM_ID + "/auto-schedule/apply"), SCHEDULER_ID, validApply)
				.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("Forbids Instructor and Student from auto-schedule endpoints (403)")
	void forbidsInstructorAndStudent() throws Exception {
		send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"), INSTRUCTOR_ID, "{}")
				.andExpect(status().isForbidden());
		send(post("/api/terms/" + TERM_ID + "/auto-schedule/preview"), STUDENT_ID, "{}")
				.andExpect(status().isForbidden());
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
}
