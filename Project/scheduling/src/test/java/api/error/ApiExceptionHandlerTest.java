package api.error;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import app.SchedulingApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import security.SecurityConfig;
import service.BusinessRuleException;
import service.ConflictingStateException;
import service.NotFoundException;

@WebMvcTest(controllers = ApiExceptionHandlerTest.ThrowingController.class)
@ContextConfiguration(classes = SchedulingApplication.class)
@Import({ ApiExceptionHandler.class, SecurityConfig.class, ApiExceptionHandlerTest.ThrowingController.class })
class ApiExceptionHandlerTest {

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("NotFoundException becomes 404 problem+json with the message as detail")
	void notFound() throws Exception {
		mvc.perform(get("/test/not-found").with(user("jellis")))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.detail").value("Room 42 was not found."));
	}

	@Test
	@DisplayName("ConflictingStateException becomes 409")
	void conflictingState() throws Exception {
		mvc.perform(get("/test/conflict").with(user("jellis")))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Request is not pending"));
	}

	@Test
	@DisplayName("BusinessRuleException becomes 422")
	void businessRule() throws Exception {
		mvc.perform(get("/test/rule").with(user("jellis")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("Availability is read-only once the term is published."));
	}

	@Test
	@DisplayName("TC-FR3.1 style: invalid body gives 400 with a plain-language detail and per-field errors")
	void validationErrors() throws Exception {
		mvc.perform(post("/test/rooms").with(user("jellis"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\",\"capacity\":-5}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Some fields need fixing before this can be saved."))
			.andExpect(jsonPath("$.errors.name").value("Room name is required."))
			.andExpect(jsonPath("$.errors.capacity").value("Capacity must be 1 or more."));
	}

	@Test
	@DisplayName("Unexpected exceptions become 500 without leaking internals")
	void unexpected() throws Exception {
		mvc.perform(get("/test/boom").with(user("jellis")))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
					org.hamcrest.Matchers.containsString("secret"))));
	}

	@Test
	@DisplayName("Access denied from @PreAuthorize stays 403, not 500")
	void accessDeniedIsNotSwallowed() throws Exception {
		mvc.perform(get("/test/scheduler-only").with(user("mbrooks").roles("STUDENT")))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("Anonymous request is 401")
	void anonymousIsUnauthorized() throws Exception {
		mvc.perform(get("/test/not-found"))
			.andExpect(status().isUnauthorized());
	}

	record RoomRequest(@NotBlank(message = "Room name is required.") String name,
			@Min(value = 1, message = "Capacity must be 1 or more.") int capacity) {
	}

	@RestController
	static class ThrowingController {

		@GetMapping("/test/not-found")
		void notFound() {
			throw NotFoundException.of("Room", 42);
		}

		@GetMapping("/test/conflict")
		void conflict() {
			throw new ConflictingStateException("Request is not pending");
		}

		@GetMapping("/test/rule")
		void rule() {
			throw new BusinessRuleException("Availability is read-only once the term is published.");
		}

		@PostMapping("/test/rooms")
		void create(@Valid @RequestBody RoomRequest body) {
		}

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("secret internal state");
		}

		@GetMapping("/test/scheduler-only")
		@PreAuthorize("hasRole('SCHEDULER')")
		void schedulerOnly() {
		}

	}

}
