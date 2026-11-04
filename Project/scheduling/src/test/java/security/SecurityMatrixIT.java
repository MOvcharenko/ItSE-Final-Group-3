package security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;
import domain.Role;
import persistence.AppUserRepository;
import service.BookingService;
import service.CalendarService;
import service.CatalogService;
import service.ImportService;
import service.NoticeService;
import service.TimetableService;

/**
 * Every endpoint x every role, using real signed tokens from the dev token endpoint. The services are mocked
 * because this test is only about who gets in. Adding an /api endpoint fails {@link #everyEndpointIsCovered()}
 * until it is listed in {@link #ENDPOINTS}.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityMatrixIT {

	private static final Set<Role> SCHEDULER = Set.of(Role.SCHEDULER);
	private static final Set<Role> STAFF = Set.of(Role.SCHEDULER, Role.INSTRUCTOR);
	private static final Set<Role> EVERYONE = Set.of(Role.values());

	private static final String WINDOW = "?start=2026-11-10T09:00:00&end=2026-11-10T10:00:00";
	private static final String SUBJECT = """
			{"courseCode":"CS 101","title":"Intro","creditHours":3,"defaultRoomType":"LAB"}""";
	private static final String ROOM = """
			{"name":"Founders 110","building":"Founders","capacity":40,"roomType":"LECTURE_HALL"}""";
	private static final String EQUIPMENT = """
			{"name":"Projector 1","itemType":"Projector","unitCount":1}""";
	private static final String STATUS = """
			{"status":"MAINTENANCE"}""";
	private static final String BOOKING = """
			{"resourceType":"ROOM","resourceId":"R1","start":"2026-11-10T09:00:00",
			"end":"2026-11-10T10:00:00","purpose":"Review session"}""";
	private static final String TERM = """
			{"name":"Fall 2026","startDate":"2026-09-01","endDate":"2026-12-20"}""";
	private static final String TERM_NAME = """
			{"name":"Fall 2026"}""";

	private static final List<Endpoint> ENDPOINTS = List.of(
			new Endpoint("GET", "/api/subjects", "", null, 200, SCHEDULER),
			new Endpoint("POST", "/api/subjects", "", SUBJECT, 201, SCHEDULER),
			new Endpoint("GET", "/api/rooms", "", null, 200, STAFF),
			new Endpoint("POST", "/api/rooms", "", ROOM, 201, SCHEDULER),
			new Endpoint("PATCH", "/api/rooms/{id}/status", "", STATUS, 200, SCHEDULER),
			new Endpoint("GET", "/api/equipment", "", null, 200, STAFF),
			new Endpoint("POST", "/api/equipment", "", EQUIPMENT, 201, SCHEDULER),
			new Endpoint("PATCH", "/api/equipment/{id}/status", "", STATUS, 200, SCHEDULER),
			new Endpoint("POST", "/api/bookings", "", BOOKING, 201, STAFF),
			new Endpoint("GET", "/api/bookings", "", null, 200, STAFF),
			new Endpoint("POST", "/api/bookings/{id}/approve", "", null, 200, SCHEDULER),
			new Endpoint("POST", "/api/bookings/{id}/cancel", "", null, 200, SCHEDULER),
			new Endpoint("GET", "/api/search/rooms", WINDOW, null, 200, STAFF),
			new Endpoint("GET", "/api/search/equipment", WINDOW, null, 200, STAFF),
			new Endpoint("GET", "/api/import/templates/{type}", "", null, 200, SCHEDULER),
			new Endpoint("POST", "/api/import/{type}", "", null, 200, SCHEDULER),
			new Endpoint("GET", "/api/instructors", "", null, 200, SCHEDULER),
			new Endpoint("GET", "/api/terms", "", null, 200, EVERYONE),
			new Endpoint("GET", "/api/terms/{id}", "", null, 200, EVERYONE),
			new Endpoint("POST", "/api/terms", "", TERM, 201, SCHEDULER),
			new Endpoint("PATCH", "/api/terms/{id}", "", TERM_NAME, 200, SCHEDULER),
			new Endpoint("DELETE", "/api/terms/{id}", "", null, 204, SCHEDULER),
			new Endpoint("POST", "/api/terms/{id}/copy", "", TERM_NAME, 201, SCHEDULER),
			new Endpoint("POST", "/api/terms/{id}/publish", "", null, 200, SCHEDULER),
			new Endpoint("GET", "/api/terms/{id}/sections", "", null, 200, SCHEDULER),
			new Endpoint("POST", "/api/terms/{id}/sections", "", "{}", 201, SCHEDULER),
			new Endpoint("PUT", "/api/terms/{id}/sections/{sectionId}", "", "{}", 200, SCHEDULER),
			new Endpoint("DELETE", "/api/terms/{id}/sections/{sectionId}", "", null, 204, SCHEDULER),
			new Endpoint("PATCH", "/api/sections/{id}/assignment", "", "{}", 200, SCHEDULER),
			new Endpoint("GET", "/api/notices", "", null, 200, EVERYONE),
			new Endpoint("POST", "/api/notices/{id}/read", "", null, 200, EVERYONE),
			new Endpoint("GET", "/api/calendar", "", null, 200, EVERYONE),
			new Endpoint("GET", "/api/calendar.ics", "", null, 200, EVERYONE));

	@Autowired
	MockMvc mvc;

	@Autowired
	AppUserRepository users;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@MockitoBean
	CatalogService catalog;

	@MockitoBean
	BookingService bookings;

	@MockitoBean
	ImportService imports;

	@MockitoBean
	TimetableService timetable;

	@MockitoBean
	NoticeService notices;

	@MockitoBean
	CalendarService calendar;

	static Stream<Arguments> everyEndpointAndRole() {
		return ENDPOINTS.stream().flatMap(e -> Stream.of(DevAccount.values()).map(a -> Arguments.of(e, a)));
	}

	static Stream<Endpoint> everyEndpoint() {
		return ENDPOINTS.stream();
	}

	@ParameterizedTest(name = "{0} as {1}")
	@MethodSource("everyEndpointAndRole")
	@DisplayName("TC-TR3.2, TC-TR3.3, TC-TR3.4: each role gets exactly the access its row allows")
	void roleMatrix(Endpoint endpoint, DevAccount account) throws Exception {
		boolean allowed = endpoint.roles().contains(account.role);
		mvc.perform(endpoint.toRequest(tokenFor(account.id)))
			.andExpect(status().is(allowed ? endpoint.success() : 403));
		if (!allowed) {
			verifyNoInteractions(catalog, bookings, imports, timetable, notices, calendar);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("everyEndpoint")
	@DisplayName("TC-NFR1.4: no token is 401 on every endpoint")
	void noToken(Endpoint endpoint) throws Exception {
		mvc.perform(endpoint.toRequest(null)).andExpect(status().isUnauthorized());
		verifyNoInteractions(catalog, bookings, imports, timetable, notices, calendar);
	}

	@Test
	@DisplayName("Every /api endpoint is in the matrix and carries @PreAuthorize")
	void everyEndpointIsCovered() {
		Set<String> mapped = new TreeSet<>();
		List<String> unguarded = new ArrayList<>();
		mappings.getHandlerMethods().forEach((info, handler) -> info.getPathPatternsCondition().getPatternValues()
			.stream().filter(path -> path.startsWith("/api/")).forEach(path -> {
				info.getMethodsCondition().getMethods().forEach(method -> mapped.add(method + " " + path));
				if (info.getMethodsCondition().isEmpty()) {
					mapped.add("ANY " + path);
				}
				if (!handler.hasMethodAnnotation(PreAuthorize.class)) {
					unguarded.add(path);
				}
			}));

		assertThat(mapped).isEqualTo(ENDPOINTS.stream().map(e -> e.method() + " " + e.path())
			.collect(Collectors.toCollection(TreeSet::new)));
		assertThat(unguarded).isEmpty();
	}

	@Test
	@DisplayName("A token with a broken signature is 401")
	void tamperedToken() throws Exception {
		String token = tokenFor(DevAccount.MAYA_BROOKS.id);
		String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA";
		mvc.perform(get("/api/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
			.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("TC-NFR1.4: a role sent in the token request is ignored")
	void roleInRequestBodyIsIgnored() throws Exception {
		String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"U03\",\"role\":\"SCHEDULER\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mvc.perform(get("/api/subjects").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("The dev token endpoint refuses an unknown account")
	void unknownAccount() throws Exception {
		mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"nobody\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("Health stays open without a token")
	void healthIsOpen() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("TC-TR3.4: a scheduler's departments are read from scheduler_department")
	void schedulerDepartments() {
		assertThat(users.findAuthorizedDepartments(DevAccount.MAYA_BROOKS.id)).containsExactlyInAnyOrder("CS", "MATH");
		assertThat(users.findAuthorizedDepartments(DevAccount.L_BIANCHI.id)).isEmpty();
	}

	private String tokenFor(String userId) throws Exception {
		String body = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"" + userId + "\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	record Endpoint(String method, String path, String query, String body, int success, Set<Role> roles) {

		RequestBuilder toRequest(String token) {
			HttpHeaders headers = new HttpHeaders();
			if (token != null) {
				headers.setBearerAuth(token);
			}
			String url = path.replace("{id}", "X1").replace("{sectionId}", "S1").replace("{type}", "rooms") + query;
			if (path.equals("/api/import/{type}")) {
				return multipart(url).file(new MockMultipartFile("file", "rooms.csv", "text/csv", "name\n".getBytes()))
					.headers(headers);
			}
			var builder = request(HttpMethod.valueOf(method), url).headers(headers);
			return body == null ? builder : builder.contentType(MediaType.APPLICATION_JSON).content(body);
		}

		@Override
		public String toString() {
			return method + " " + path;
		}
	}

}
