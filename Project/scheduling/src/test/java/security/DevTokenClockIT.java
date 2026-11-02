package security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import service.BookingService;
import service.CatalogService;
import service.ImportService;
import support.FixedClock;

/** The test plan's dates are in the past, so a token issued under a pinned clock must still be accepted. */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DevTokenClockIT.PinnedClock.class)
class DevTokenClockIT {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CatalogService catalog;

	@MockitoBean
	BookingService bookings;

	@MockitoBean
	ImportService imports;

	@Test
	@DisplayName("A token issued under a pinned past clock is accepted")
	void pinnedClock() throws Exception {
		String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"U01\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mvc.perform(get("/api/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk());
	}

	@TestConfiguration
	static class PinnedClock {

		@Bean
		@Primary
		Clock pinnedClock() {
			return FixedClock.oct14();
		}

	}

}
