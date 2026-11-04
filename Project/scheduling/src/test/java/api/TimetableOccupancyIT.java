package api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;

/**
 * TC-FR2.4: availability search against real sections, through the real API and database. Requests really commit and
 * a Published term hides Drafts on its dates, so every test has its own year and unique names.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimetableOccupancyIT {

	private static final String SCHEDULER = "U01";
	private static final String BIANCHI = "U02";
	private static final String MONDAY_TEN = "{\"pattern\":\"WEEKLY\",\"day\":\"MONDAY\",\"start\":\"10:00\",\"end\":\"10:50\"}";
	private static final String WEDNESDAY_TEN = "{\"pattern\":\"WEEKLY\",\"day\":\"WEDNESDAY\",\"start\":\"10:00\",\"end\":\"10:50\"}";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;
	@Autowired
	EquipmentRepository equipment;

	@Test
	@DisplayName("TC-FR2.4: a weekly class hides its room on that day and time, within the term only")
	void weeklyClass() throws Exception {
		String term = term("2051-09-01", "2051-12-20"); // 4 Sep 2051 is a Monday
		String room = room();
		place(term, room, null, MONDAY_TEN);

		assertThat(freeRooms("2051-09-04T10:30:00", "2051-09-04T11:00:00")).doesNotContain(room);
		assertThat(freeRooms("2051-09-05T10:30:00", "2051-09-05T11:00:00")).as("another weekday").contains(room);
		assertThat(freeRooms("2051-08-28T10:30:00", "2051-08-28T11:00:00")).as("the Monday before the term").contains(room);
		assertThat(freeRooms("2051-12-25T10:30:00", "2051-12-25T11:00:00")).as("the Monday after the term").contains(room);
	}

	@Test
	@DisplayName("TC-FR2.4: a window touching a class does not overlap it")
	void touchingIsNotOverlapping() throws Exception {
		String term = term("2052-09-01", "2052-12-20"); // 2 Sep 2052 is a Monday
		String room = room();
		place(term, room, null, MONDAY_TEN);

		assertThat(freeRooms("2052-09-02T10:50:00", "2052-09-02T12:00:00")).contains(room);
		assertThat(freeRooms("2052-09-02T09:00:00", "2052-09-02T10:00:00")).contains(room);
		assertThat(freeRooms("2052-09-02T10:49:00", "2052-09-02T12:00:00")).doesNotContain(room);
	}

	@Test
	@DisplayName("TC-FR2.4: a window across several days finds a class on any of them")
	void severalDays() throws Exception {
		String term = term("2053-09-01", "2053-12-20"); // 1 Sep 2053 is a Monday
		String room = room();
		place(term, room, null, WEDNESDAY_TEN);

		assertThat(freeRooms("2053-09-02T12:00:00", "2053-09-04T09:00:00")).doesNotContain(room);
		assertThat(freeRooms("2053-09-02T12:00:00", "2053-09-03T09:00:00")).as("ends before Wednesday's class").contains(room);
	}

	@Test
	@DisplayName("TC-FR2.4: an exam hides its room on its own date only")
	void exam() throws Exception {
		String term = term("2054-09-01", "2054-12-20");
		String room = room();
		place(term, room, null, "{\"pattern\":\"EXAM\",\"date\":\"2054-12-10\",\"start\":\"09:00\",\"end\":\"11:00\"}");

		assertThat(freeRooms("2054-12-10T10:00:00", "2054-12-10T10:30:00")).doesNotContain(room);
		assertThat(freeRooms("2054-12-17T10:00:00", "2054-12-17T10:30:00")).contains(room);
	}

	@Test
	@DisplayName("TC-FR2.4: a class in a Draft term counts while the term is being planned")
	void draftTerm() throws Exception {
		String term = term("2055-09-01", "2055-12-20"); // 6 Sep 2055 is a Monday
		String room = room();
		place(term, room, null, MONDAY_TEN);

		assertThat(freeRooms("2055-09-06T10:00:00", "2055-09-06T10:30:00")).doesNotContain(room);
	}

	@Test
	@DisplayName("TC-FR2.4: a Draft copy of a Published term does not count the same equipment twice")
	void copiedTermCountsOnce() throws Exception {
		String term = term("2056-09-01", "2056-12-20"); // 4 Sep 2056 is a Monday
		String item = equipmentItem(3);
		place(term, room(), equipmentJson(item, 1), MONDAY_TEN);
		send(post("/api/terms/" + term + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		send(post("/api/terms/" + term + "/copy"), SCHEDULER, "{\"name\":\"" + unique("Copy") + "\"}")
			.andExpect(status().isCreated());

		assertThat(freeUnits(item, "2056-09-04T10:00:00", "2056-09-04T10:30:00")).containsExactly(2);
	}

	@Test
	@DisplayName("TC-FR2.4: sections' equipment units add up, each section once, and a fully used item is hidden")
	void equipmentUnits() throws Exception {
		String term = term("2057-09-01", "2057-12-20"); // 3 Sep 2057 is a Monday
		String item = equipmentItem(3);
		place(term, room(), equipmentJson(item, 1), MONDAY_TEN + "," + WEDNESDAY_TEN);

		assertThat(freeUnits(item, "2057-09-03T00:00:00", "2057-09-06T00:00:00")).as("meets twice, counts once")
			.containsExactly(2);

		place(term, room(), equipmentJson(item, 2), MONDAY_TEN);

		assertThat(freeUnits(item, "2057-09-03T10:00:00", "2057-09-03T10:30:00")).isEmpty();
		assertThat(freeUnits(item, "2057-09-05T10:00:00", "2057-09-05T10:30:00")).as("Wednesday").containsExactly(2);
	}

	@Test
	@DisplayName("TC-FR2.3b: a request made before a class was placed cannot be approved afterwards")
	void approvalRefusedOnceAClassUsesTheRoom() throws Exception {
		String term = term("2058-09-01", "2058-12-20"); // 2 Sep 2058 is a Monday
		String room = room();
		String clashing = request("ROOM", room, "2058-09-02T10:30:00", "2058-09-02T11:30:00");
		String touching = request("ROOM", room, "2058-09-02T10:50:00", "2058-09-02T11:30:00");
		place(term, room, null, MONDAY_TEN);

		send(post("/api/bookings/" + clashing + "/approve"), SCHEDULER, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("has a class scheduled")));
		send(post("/api/bookings/" + touching + "/approve"), SCHEDULER, null).andExpect(status().isOk());
	}

	@Test
	@DisplayName("OI-1: a request cannot be approved when classes need every unit of the item")
	void approvalRefusedWhenClassesNeedEveryUnit() throws Exception {
		String term = term("2059-09-01", "2059-12-20"); // 1 Sep 2059 is a Monday
		String item = equipmentItem(2);
		String clashing = request("EQUIPMENT", item, "2059-09-01T10:00:00", "2059-09-01T10:30:00");
		String nextDay = request("EQUIPMENT", item, "2059-09-02T10:00:00", "2059-09-02T10:30:00");
		place(term, room(), equipmentJson(item, 2), MONDAY_TEN);

		send(post("/api/bookings/" + clashing + "/approve"), SCHEDULER, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value(containsString("all 2 units")));
		send(post("/api/bookings/" + nextDay + "/approve"), SCHEDULER, null).andExpect(status().isOk());
	}

	private String request(String type, String resource, String start, String end) throws Exception {
		String body = send(post("/api/bookings"), BIANCHI, "{\"resourceType\":\"" + type + "\",\"resourceId\":\"" + resource
				+ "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\",\"purpose\":\"A talk\"}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private List<String> freeRooms(String start, String end) throws Exception {
		String body = send(get("/api/search/rooms").param("start", start).param("end", end), BIANCHI, null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].id");
	}

	private List<Integer> freeUnits(String item, String start, String end) throws Exception {
		String body = send(get("/api/search/equipment").param("start", start).param("end", end), BIANCHI, null)
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[?(@.id=='" + item + "')].freeUnits");
	}

	private void place(String term, String room, String equipmentOrNull, String meetings) throws Exception {
		String subject = subjects.save(SubjectEntity.create(unique("C"), "A course", 3, RoomType.LECTURE_HALL, "MATH", Set.of()))
			.getId();
		send(post("/api/terms/" + term + "/sections"), SCHEDULER, "{\"subjectId\":\"" + subject + "\",\"roomId\":\"" + room
				+ "\",\"instructorIds\":[\"" + BIANCHI + "\"],\"meetings\":[" + meetings + "]"
				+ (equipmentOrNull == null ? "" : ",\"equipment\":[" + equipmentOrNull + "]") + "}")
			.andExpect(status().isCreated());
	}

	private static String equipmentJson(String item, int units) {
		return "{\"equipmentId\":\"" + item + "\",\"units\":" + units + "}";
	}

	private String term(String start, String end) throws Exception {
		String body = send(post("/api/terms"), SCHEDULER, "{\"name\":\"" + unique("Term") + "\",\"startDate\":\"" + start
				+ "\",\"endDate\":\"" + end + "\"}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String room() {
		return rooms.save(RoomEntity.create(unique("Room"), "Founders", 40, RoomType.LECTURE_HALL, Set.of())).getId();
	}

	private String equipmentItem(int units) {
		return equipment.save(EquipmentEntity.create(unique("Item"), "Projector", units, "Store")).getId();
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
