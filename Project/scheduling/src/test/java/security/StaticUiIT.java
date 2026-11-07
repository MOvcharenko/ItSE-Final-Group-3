package security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import app.SchedulingApplication;

/** The UI's own files load without a token, because the browser has none until the page has signed in. */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaticUiIT {

	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = { "/", "/index.html", "/signin-callback.html", "/auth/config", "/css/app.css", "/js/app.js",
			"/js/api.js", "/js/bookings.js", "/js/calendar.js", "/js/catalog.js", "/js/conflicts.js", "/js/dates.js",
			"/js/dom.js", "/js/forms.js", "/js/import.js", "/js/insights.js", "/js/notices.js", "/js/search.js",
			"/js/signin-callback.js", "/js/teaching.js", "/js/timetable.js", "/js/timetable-grid.js" })
	@DisplayName("The static UI is served without a token")
	void uiFilesAreOpen(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/calendar", "/api/notices", "/api/terms", "/no-such-page.html" })
	@DisplayName("TC-NFR1.4: opening the UI files leaves everything else behind the token")
	void everythingElseStaysClosed(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("The test profile tells the browser to use the dev-account picker, with the test accounts to offer")
	void signInConfigIsDevInTests() throws Exception {
		mvc.perform(get("/auth/config"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mode").value("dev"))
				.andExpect(jsonPath("$.accounts.length()").value(DevAccount.values().length))
				.andExpect(jsonPath("$.accounts[0].id").value("U01"))
				.andExpect(jsonPath("$.accounts[0].name").value("Maya Brooks"))
				.andExpect(jsonPath("$.accounts[0].role").value("SCHEDULER"))
				.andExpect(jsonPath("$.accounts[2].role").value("STUDENT"));
	}

	@Test
	@DisplayName("Only reading the UI files is open")
	void writingToUiPathsNeedsAToken() throws Exception {
		mvc.perform(post("/js/app.js")).andExpect(status().isUnauthorized());
	}

}
