package api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
import domain.TermStatus;
import persistence.InstructorAvailabilityEntity;
import persistence.InstructorAvailabilityRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import support.Td1Loader;

/**
 * Integration tests for Instructor Availability & Lens API (UC-14, FR1.4, NFR3.4, TC-FR4.6).
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AvailabilityApiIT {

	private static final String TERM_ID = "T-2026-FALL";
	private static final String INSTRUCTOR_ID = "U02"; // L. Bianchi
	private static final String SCHEDULER_ID = "U01";  // Maya Brooks

	@Autowired
	MockMvc mvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	TermRepository terms;

	@Autowired
	InstructorAvailabilityRepository availabilityRepo;

	@BeforeEach
	void loadFixture() throws Exception {
		Connection c = DataSourceUtils.getConnection(dataSource);
		Td1Loader.resetAndLoad(c);
	}

	@Test
	@DisplayName("UC-14 / GET: Instructor retrieves own declared availability and summary")
	void getMyAvailability() throws Exception {
		send(get("/api/terms/" + TERM_ID + "/availability/me"), INSTRUCTOR_ID, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.termId").value(TERM_ID))
				.andExpect(jsonPath("$.instructorId").value(INSTRUCTOR_ID))
				.andExpect(jsonPath("$.instructorName").value("L. Bianchi"))
				.andExpect(jsonPath("$.readOnly").value(false))
				.andExpect(jsonPath("$.blocks").isArray())
				.andExpect(jsonPath("$.summary.openHours").value(14.0))
				.andExpect(jsonPath("$.summary.preferHours").value(4.0))
				.andExpect(jsonPath("$.summary.totalHours").value(18.0));
	}

	@Test
	@DisplayName("UC-14 / PUT: Instructor submits availability, equipment requests, and notes for a Draft term")
	void submitMyAvailabilitySuccess() throws Exception {
		String payload = """
				{
				  "blocks": [
				    {
				      "dayOfWeek": "MONDAY",
				      "startTime": "09:00:00",
				      "endTime": "12:00:00",
				      "status": "OPEN"
				    },
				    {
				      "dayOfWeek": "WEDNESDAY",
				      "startTime": "13:00:00",
				      "endTime": "16:00:00",
				      "status": "PREFER"
				    },
				    {
				      "dayOfWeek": "FRIDAY",
				      "startTime": "14:00:00",
				      "endTime": "17:00:00",
				      "status": "UNAVAILABLE"
				    }
				  ],
				  "equipmentNeeds": ["projector", "laptop cart"],
				  "notes": "Prefer afternoons on Wednesdays."
				}
				""";

		send(put("/api/terms/" + TERM_ID + "/availability/me"), INSTRUCTOR_ID, payload)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.openHours").value(3.0))
				.andExpect(jsonPath("$.summary.preferHours").value(3.0))
				.andExpect(jsonPath("$.summary.unavailableHours").value(3.0))
				.andExpect(jsonPath("$.summary.totalHours").value(9.0))
				.andExpect(jsonPath("$.equipmentNeeds.length()").value(2))
				.andExpect(jsonPath("$.notes").value("Prefer afternoons on Wednesdays."));

		List<InstructorAvailabilityEntity> saved = availabilityRepo.findByTermAndInstructor(TERM_ID, INSTRUCTOR_ID);
		assertEquals(3, saved.size());
	}

	@Test
	@DisplayName("NFR3.4 / UC-14 6a: Availability submission is rejected if term is PUBLISHED")
	void submitAvailabilityRejectedWhenPublished() throws Exception {
		TermEntity term = terms.findById(TERM_ID).orElseThrow();
		term.publish(LocalDateTime.now());
		terms.saveAndFlush(term);

		String payload = """
				{
				  "blocks": [
				    {
				      "dayOfWeek": "MONDAY",
				      "startTime": "09:00:00",
				      "endTime": "12:00:00",
				      "status": "OPEN"
				    }
				  ]
				}
				""";

		send(put("/api/terms/" + TERM_ID + "/availability/me"), INSTRUCTOR_ID, payload)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is published. Instructor availability is locked")));
	}

	@Test
	@DisplayName("FR1.4 / TC-FR1.4: Scheduler gets availability lens overlay for timetable builder")
	void getInstructorAvailabilityLens() throws Exception {
		send(get("/api/instructors/U04/availability?termId=" + TERM_ID), SCHEDULER_ID, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.instructorId").value("U04"))
				.andExpect(jsonPath("$.instructorName").value("Dr. Novak"))
				.andExpect(jsonPath("$.termId").value(TERM_ID))
				.andExpect(jsonPath("$.blocks").isArray())
				.andExpect(jsonPath("$.blocks.length()").value(4));
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
