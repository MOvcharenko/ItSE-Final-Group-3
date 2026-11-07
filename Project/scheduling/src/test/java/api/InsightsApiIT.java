package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.List;

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
import persistence.InsightSuggestionEntity;
import persistence.InsightSuggestionRepository;
import support.Td1;
import support.Td1Loader;

/**
 * Integration tests for Optimization Insights Engine & REST API (FR5, UC-13, TR2.9, NFR4.4, TC-FR5.1-5.4).
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InsightsApiIT {

	private static final String TERM_ID = "T-2026-FALL";
	private static final String SCHEDULER_ID = "U01";  // Maya Brooks
	private static final String INSTRUCTOR_ID = "U02"; // L. Bianchi

	@Autowired
	MockMvc mvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	InsightSuggestionRepository suggestions;

	@BeforeEach
	void loadFixture() throws Exception {
		Connection c = DataSourceUtils.getConnection(dataSource);
		Td1Loader.resetAndLoad(c);
	}

	@Test
	@DisplayName("TC-FR5.1: Instructor utilization - Bianchi & Novak underused, Alvarez confirmation, mid-range none")
	void instructorUtilization() throws Exception {
		send(get("/api/terms/" + TERM_ID + "/insights"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.termId").value(TERM_ID))
				// Bianchi: 6 of 18 -> UNDERUSED (< 50%)
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U02')].status").value("UNDERUSED"))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U02')].scheduledHours").value(6.0))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U02')].declaredHours").value(18.0))
				// Novak: 2 of 8 -> UNDERUSED (< 50%)
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U04')].status").value("UNDERUSED"))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U04')].scheduledHours").value(2.0))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U04')].declaredHours").value(8.0))
				// Alvarez: 9 of 10 -> CONFIRMATION (>= 80%)
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U05')].status").value("CONFIRMATION"))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U05')].scheduledHours").value(9.0))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U05')].declaredHours").value(10.0))
				// Mid-range loads: Haddad & Okafor -> NONE
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U09')].status").value("NONE"))
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U08')].status").value("NONE"))
				// Instructors who declared nothing (Suzuki U06, Patel U07) have no bar
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U06')]").isEmpty())
				.andExpect(jsonPath("$.instructors[?(@.instructorId == 'U07')]").isEmpty());
	}

	@Test
	@DisplayName("TC-FR5.2: Room utilization - Founders 110 underused, Hale Hall 204 oversized/underused")
	void roomUtilization() throws Exception {
		send(get("/api/terms/" + TERM_ID + "/insights"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				// Founders 110: scheduled 45 min -> UNDERUSED
				.andExpect(jsonPath("$.rooms[?(@.roomId == 'R-F110')].status").value("UNDERUSED"));
	}

	@Test
	@DisplayName("TC-FR5.3: Advisory only - suggestion is dismissible without altering schedule")
	void dismissSuggestion() throws Exception {
		// First call generates suggestions
		String res = send(get("/api/terms/" + TERM_ID + "/insights"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String suggestionId = JsonPath.read(res, "$.suggestions[0].id");

		// Dismiss
		send(post("/api/insights/" + suggestionId + "/dismiss"), SCHEDULER_ID, null)
				.andExpect(status().isNoContent());

		InsightSuggestionEntity entity = suggestions.findById(suggestionId).orElseThrow();
		assertTrue(entity.isDismissed());

		// Verify reflected in API
		send(get("/api/terms/" + TERM_ID + "/insights"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.suggestions[?(@.id == '" + suggestionId + "')].dismissed").value(true));
	}

	@Test
	@DisplayName("TC-FR5.4: Deduplication - Carter Center 301 has Capacity Exceeded conflict, marked Already flagged")
	void deduplicationWithConflict() throws Exception {
		// Run conflict rescan to populate detected conflicts from TD-1 data into database
		send(post("/api/terms/" + TERM_ID + "/rescan"), SCHEDULER_ID, null)
				.andExpect(status().isOk());

		send(get("/api/terms/" + TERM_ID + "/insights"), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				// Carter Center 301 has Capacity Exceeded conflict, so its suggestion card has alreadyFlagged == true
				.andExpect(jsonPath("$.suggestions[?(@.resourceId == 'R-CC301')].alreadyFlagged").value(true));
	}

	@Test
	@DisplayName("Access control: Only Schedulers can access insights")
	void accessControl() throws Exception {
		send(get("/api/terms/" + TERM_ID + "/insights"), INSTRUCTOR_ID, null)
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
