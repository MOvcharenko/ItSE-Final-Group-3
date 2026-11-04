package api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;

import app.SchedulingApplication;
import domain.NoticeEvent;
import domain.RoomType;
import integration.SisClient;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.NoticeEntity;
import persistence.NoticeRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import service.BookingService;
import service.CatalogService;
import service.ImportService;
import service.NoticeService;
import service.NotFoundException;

/**
 * Notices end to end: a real edit through the API, saved in the real database, read back as each user. Only the SIS
 * and Dev C's services are mocked. Users keep notices between tests, so every check looks at one section.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NoticeApiIT {

	private static final String SCHEDULER = "U01";
	private static final String BIANCHI = "U02";
	private static final String STUDENT = "U03";
	private static final String MONDAY = "[{\"pattern\":\"WEEKLY\",\"day\":\"MONDAY\",\"start\":\"10:00\",\"end\":\"10:50\"}]";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;
	@Autowired
	AppUserRepository users;
	@Autowired
	BookingRepository bookingRepository;
	@Autowired
	NoticeRepository noticeRepository;
	@Autowired
	NoticeService notices;
	@Autowired
	ThreadPoolTaskExecutor taskExecutor;
	@Autowired
	HikariDataSource dataSource;
	@Autowired
	PlatformTransactionManager transactions;

	@MockitoBean
	SisClient sis;
	@MockitoBean
	CatalogService catalog;
	@MockitoBean
	BookingService bookings;
	@MockitoBean
	ImportService imports;

	private String courseCode;
	private String subjectId;
	private String roomId;
	private String otherRoomName;
	private String otherRoomId;
	private String termId;

	@BeforeEach
	void setUp() throws Exception {
		when(sis.enrolledStudentIds(anyString())).thenReturn(List.of(STUDENT));
		courseCode = unique("MATH");
		subjectId = subjects.save(SubjectEntity.create(courseCode, "A course", 3, RoomType.LAB, "MATH", Set.of())).getId();
		roomId = room(unique("Room"));
		otherRoomName = unique("Room");
		otherRoomId = room(otherRoomName);
		String body = send(post("/api/terms"), SCHEDULER, "{\"name\":\"" + unique("Fall")
				+ "\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-12-20\"}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		termId = JsonPath.read(body, "$.id");
	}

	@Test
	@DisplayName("UC-18: a change in a Published term reaches the instructor and enrolled students, not the Scheduler")
	void publishedChange() throws Exception {
		String sectionId = placeAndPublish();

		changeRoom(sectionId);

		String expected = courseCode + " section 01 has changed. It is in " + otherRoomName
				+ " and meets Mon 10:00–10:50. Instructors: L. Bianchi.";
		assertThat(messages(BIANCHI, sectionId)).containsExactly(expected);
		assertThat(messages(STUDENT, sectionId)).containsExactly(expected);
		assertThat(messages(SCHEDULER, sectionId)).isEmpty();
	}

	@Test
	@DisplayName("UC-18: a Scheduler who also teaches the section is told, like any instructor")
	void schedulerWhoTeaches() throws Exception {
		String sectionId = place(instructors(SCHEDULER));
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		changeRoom(sectionId);

		assertThat(messages(SCHEDULER, sectionId)).hasSize(1);
	}

	@Test
	@DisplayName("UC-18: a notice carries its event and links to its section and term, and starts unread")
	void noticeDetails() throws Exception {
		String sectionId = placeAndPublish();
		changeRoom(sectionId);
		allDelivered();

		send(get("/api/notices"), STUDENT, null)
			.andExpect(jsonPath("$[?(@.sectionId == '" + sectionId + "')].event").value("SECTION_CHANGED"))
			.andExpect(jsonPath("$[?(@.sectionId == '" + sectionId + "')].termId").value(termId))
			.andExpect(jsonPath("$[?(@.sectionId == '" + sectionId + "')].read").value(false));
	}

	@Test
	@DisplayName("UC-18: a section with very many meetings still saves its change, and the notice names the new room")
	void veryLongNotice() throws Exception {
		String meetings = IntStream.rangeClosed(1, 28).boxed()
			.flatMap(day -> Stream.of(9, 11).map(hour -> String.format(
					"{\"pattern\":\"ONE_TIME\",\"date\":\"2026-10-%02d\",\"start\":\"%02d:00\",\"end\":\"%02d:50\"}", day, hour, hour)))
			.collect(Collectors.joining(",", "[", "]"));
		String body = send(post("/api/terms/" + termId + "/sections"), SCHEDULER,
				placement(roomId, instructors(BIANCHI)).replace(MONDAY, meetings))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String sectionId = JsonPath.read(body, "$.id");
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		changeRoom(sectionId);

		send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].roomId").value(otherRoomId));
		List<String> received = messages(STUDENT, sectionId);
		assertThat(received).hasSize(1);
		assertThat(received.get(0)).hasSizeLessThanOrEqualTo(1000).contains("is in " + otherRoomName)
			.endsWith("See your calendar for the full details.");
	}

	@Test
	@DisplayName("UC-18: changes to a Draft notify nobody")
	void draftIsSilent() throws Exception {
		String sectionId = place(instructors(BIANCHI));

		changeRoom(sectionId);

		assertThat(messages(BIANCHI, sectionId)).isEmpty();
		assertThat(messages(STUDENT, sectionId)).isEmpty();
	}

	@Test
	@DisplayName("UC-18: adding a section to a Published term notifies its instructor and students")
	void addedToPublishedTerm() throws Exception {
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());

		String sectionId = place(instructors(BIANCHI));

		assertThat(messages(BIANCHI, sectionId)).hasSize(1);
		assertThat(messages(STUDENT, sectionId)).hasSize(1);
	}

	@Test
	@DisplayName("UC-18: a removed instructor is told, and the new one and students get the new names")
	void instructorReplaced() throws Exception {
		String sectionId = placeAndPublish();
		String replacement = instructor("A. Novak");

		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER,
				"{\"instructorIds\":" + instructors(replacement) + "}")
			.andExpect(status().isOk());

		String changed = "The instructors for " + courseCode + " section 01 have changed. Now teaching: A. Novak.";
		assertThat(messages(BIANCHI, sectionId)).containsExactly("You are no longer teaching " + courseCode + " section 01.");
		assertThat(storedFor(replacement)).containsExactly(changed);
		assertThat(messages(STUDENT, sectionId)).containsExactly(changed);
	}

	@Test
	@DisplayName("UC-18: changing the room and instructor together sends one notice with both, and asks the SIS once")
	void roomAndInstructorTogether() throws Exception {
		String sectionId = placeAndPublish();
		String replacement = instructor("A. Novak");

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER,
				placement(otherRoomId, instructors(replacement)))
			.andExpect(status().isOk());

		String combined = courseCode + " section 01 has changed. It is in " + otherRoomName
				+ " and meets Mon 10:00–10:50. Instructors: A. Novak.";
		assertThat(messages(STUDENT, sectionId)).containsExactly(combined);
		assertThat(storedFor(replacement)).containsExactly(combined);
		assertThat(messages(BIANCHI, sectionId)).containsExactly("You are no longer teaching " + courseCode + " section 01.");
		verify(sis, times(1)).enrolledStudentIds(sectionId);
	}

	@Test
	@DisplayName("UC-18: an edit that changes nothing sends nothing")
	void noOpEdit() throws Exception {
		String sectionId = placeAndPublish();

		send(put("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, placement(roomId, instructors(BIANCHI)))
			.andExpect(status().isOk());

		assertThat(messages(STUDENT, sectionId)).isEmpty();
	}

	@Test
	@DisplayName("UC-18: a cancellation notice survives the section it describes")
	void cancelled() throws Exception {
		String sectionId = placeAndPublish();

		send(delete("/api/terms/" + termId + "/sections/" + sectionId), SCHEDULER, null).andExpect(status().isNoContent());
		allDelivered();

		String body = send(get("/api/notices"), STUDENT, null).andReturn().getResponse().getContentAsString();
		List<String> found = JsonPath.read(body, "$[?(@.message == '" + courseCode + " section 01 has been cancelled.')].event");
		List<Object> links = JsonPath.read(body, "$[?(@.message == '" + courseCode + " section 01 has been cancelled.')].sectionId");
		assertThat(found).containsExactly("SECTION_CANCELLED");
		assertThat(links).containsOnlyNulls();
	}

	@Test
	@DisplayName("UC-18: someone who teaches and is enrolled in a section gets one notice, not two")
	void noDuplicateRecipients() throws Exception {
		when(sis.enrolledStudentIds(anyString())).thenReturn(List.of(STUDENT, BIANCHI));
		String sectionId = placeAndPublish();

		changeRoom(sectionId);

		assertThat(messages(BIANCHI, sectionId)).hasSize(1);
	}

	@Test
	@DisplayName("UC-06 3a: with the SIS down, the change saves and instructors are still told")
	void sisDown() throws Exception {
		String sectionId = placeAndPublish();
		doThrow(new IllegalStateException("SIS unavailable")).when(sis).enrolledStudentIds(eq(sectionId));

		changeRoom(sectionId);

		send(get("/api/terms/" + termId + "/sections"), SCHEDULER, null)
			.andExpect(jsonPath("$[0].roomId").value(otherRoomId));
		assertThat(messages(BIANCHI, sectionId)).hasSize(1);
		assertThat(messages(STUDENT, sectionId)).isEmpty();
	}

	@Test
	@DisplayName("A slow SIS holds up neither the edit, nor the next change to the term, nor a database connection")
	void slowSis() throws Exception {
		String sectionId = placeAndPublish();
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch answer = new CountDownLatch(1);
		AtomicBoolean answered = new AtomicBoolean();
		when(sis.enrolledStudentIds(sectionId)).thenAnswer(call -> {
			asked.countDown();
			answer.await(10, TimeUnit.SECONDS);
			answered.set(true);
			return List.of(STUDENT);
		});

		try {
			changeRoom(sectionId);
			assertThat(answered).isFalse();
			assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();

			send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER, "{\"roomId\":\"" + roomId + "\"}")
				.andExpect(status().isOk());
			assertThat(answered).isFalse();
			await().atMost(Duration.ofSeconds(5))
				.until(() -> dataSource.getHikariPoolMXBean().getActiveConnections() == 0);
			assertThat(storedFor(BIANCHI)).filteredOn(message -> message.contains(courseCode)).hasSize(2);
			assertThat(storedFor(STUDENT)).noneMatch(message -> message.contains(courseCode));
		} finally {
			answer.countDown();
		}

		assertThat(messages(STUDENT, sectionId)).hasSize(2);
	}

	@Test
	@DisplayName("A change that rolls back tells nobody, and the SIS is not asked")
	void rolledBack() throws Exception {
		String sectionId = placeAndPublish();

		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			notices.notifyAffected(sectionId, NoticeEvent.SECTION_CHANGED);
			status.setRollbackOnly();
		});

		assertThat(messages(BIANCHI, sectionId)).isEmpty();
		verify(sis, never()).enrolledStudentIds(sectionId);
	}

	@Test
	@DisplayName("UC-18: a user marks their own notice read, and marking it twice is harmless")
	void markRead() throws Exception {
		String sectionId = placeAndPublish();
		changeRoom(sectionId);
		String noticeId = noticeId(STUDENT, sectionId);

		send(post("/api/notices/" + noticeId + "/read"), STUDENT, null)
			.andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
		send(post("/api/notices/" + noticeId + "/read"), STUDENT, null).andExpect(status().isOk());

		send(get("/api/notices"), STUDENT, null)
			.andExpect(jsonPath("$[?(@.id == '" + noticeId + "')].read").value(true));
		send(get("/api/notices"), BIANCHI, null)
			.andExpect(jsonPath("$[?(@.sectionId == '" + sectionId + "')].read").value(false));
	}

	@Test
	@DisplayName("TC-TR3.2: a user cannot see or mark another user's notice")
	void othersNotices() throws Exception {
		String sectionId = placeAndPublish();
		changeRoom(sectionId);
		String studentNotice = noticeId(STUDENT, sectionId);

		send(post("/api/notices/" + studentNotice + "/read"), BIANCHI, null).andExpect(status().isNotFound());

		send(get("/api/notices"), BIANCHI, null)
			.andExpect(jsonPath("$[?(@.id == '" + studentNotice + "')]").isEmpty());
		send(get("/api/notices"), STUDENT, null)
			.andExpect(jsonPath("$[?(@.id == '" + studentNotice + "')].read").value(false));
	}

	@Test
	@DisplayName("UC-08: booking decisions reach the requester, with no section link")
	void bookingDecisions() {
		String requester = instructor("Requester");
		RoomEntity room = rooms.findById(roomId).orElseThrow();
		BookingEntity booking = BookingEntity.forRoom(users.findById(requester).orElseThrow(), room,
				LocalDateTime.of(2026, 11, 10, 17, 0), LocalDateTime.of(2026, 11, 10, 18, 0), "Review session",
				LocalDateTime.of(2026, 11, 1, 9, 0));
		booking.markCancelled(SCHEDULER, LocalDateTime.of(2026, 11, 2, 9, 0), "Room closed");
		String bookingId = bookingRepository.save(booking).getId();
		String when = room.getName() + " on Nov 10, 17:00–18:00";

		notices.notifyRequester(bookingId, NoticeEvent.BOOKING_APPROVED);
		notices.notifyRequester(bookingId, NoticeEvent.BOOKING_DECLINED);
		notices.notifyRequester(bookingId, NoticeEvent.BOOKING_CANCELLED);

		List<NoticeEntity> stored = noticeRepository.findByRecipientIdOrderByCreatedAtDesc(requester);
		assertThat(stored).extracting(NoticeEntity::getMessage).containsExactlyInAnyOrder(
				"Your request for " + when + " was approved.",
				"Your request for " + when + " was declined. Note: Room closed",
				"Your booking of " + when + " was cancelled. Note: Room closed");
		assertThat(stored).allSatisfy(notice -> {
			assertThat(notice.getSectionId()).isNull();
			assertThat(notice.getTermId()).isNull();
		});
	}

	@Test
	@DisplayName("UC-18: a user's notices are listed newest first")
	void newestFirst() throws Exception {
		String older = unique("older");
		String newer = unique("newer");
		noticeRepository.save(NoticeEntity.create(STUDENT, null, null, NoticeEvent.SECTION_CHANGED, older,
				LocalDateTime.of(2030, 1, 1, 9, 0)));
		noticeRepository.save(NoticeEntity.create(STUDENT, null, null, NoticeEvent.SECTION_CHANGED, newer,
				LocalDateTime.of(2030, 1, 2, 9, 0)));

		String body = send(get("/api/notices"), STUDENT, null).andReturn().getResponse().getContentAsString();
		List<String> all = JsonPath.read(body, "$[*].message");

		assertThat(all.indexOf(newer)).isLessThan(all.indexOf(older));
	}

	@Test
	@DisplayName("Notifying about an unknown section or booking, or with the wrong event, is refused")
	void badCalls() throws Exception {
		assertThatThrownBy(() -> notices.notifyAffected("nope", NoticeEvent.SECTION_CHANGED))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> notices.notifyRequester("nope", NoticeEvent.BOOKING_APPROVED))
			.isInstanceOf(NotFoundException.class);
		String sectionId = placeAndPublish();
		assertThatThrownBy(() -> notices.notifyAffected(sectionId, NoticeEvent.BOOKING_APPROVED))
			.isInstanceOf(IllegalArgumentException.class);
	}

	// ---- Helpers ----

	private String placeAndPublish() throws Exception {
		String sectionId = place(instructors(BIANCHI));
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		return sectionId;
	}

	private String place(String instructors) throws Exception {
		String body = send(post("/api/terms/" + termId + "/sections"), SCHEDULER, placement(roomId, instructors))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private void changeRoom(String sectionId) throws Exception {
		send(patch("/api/sections/" + sectionId + "/assignment"), SCHEDULER, "{\"roomId\":\"" + otherRoomId + "\"}")
			.andExpect(status().isOk());
	}

	/** Students' notices are sent on the task pool, so a check must wait for it, or a missing notice could pass as late. */
	private void allDelivered() {
		ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();
		await().atMost(Duration.ofSeconds(15)).until(() -> pool.getCompletedTaskCount() == pool.getTaskCount());
	}

	private List<String> messages(String user, String sectionId) throws Exception {
		allDelivered();
		String body = send(get("/api/notices"), user, null).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[?(@.sectionId == '" + sectionId + "')].message");
	}

	/** For users with no dev account, who cannot get a token. */
	private List<String> storedFor(String user) {
		return noticeRepository.findByRecipientIdOrderByCreatedAtDesc(user).stream().map(NoticeEntity::getMessage).toList();
	}

	private String noticeId(String user, String sectionId) throws Exception {
		allDelivered();
		String body = send(get("/api/notices"), user, null).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$[?(@.sectionId == '" + sectionId + "')].id");
		return ids.get(0);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
		String response = mvc.perform(post("/dev/token").contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":\"" + user + "\"}"))
			.andReturn().getResponse().getContentAsString();
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(response, "$.accessToken"));
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(request);
	}

	private String placement(String room, String instructors) {
		return "{\"subjectId\":\"" + subjectId + "\",\"roomId\":\"" + room + "\",\"instructorIds\":" + instructors
				+ ",\"meetings\":" + MONDAY + "}";
	}

	private static String instructors(String... ids) {
		return "[\"" + String.join("\",\"", ids) + "\"]";
	}

	private static String unique(String prefix) {
		return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	private String room(String name) {
		return rooms.save(RoomEntity.create(name, "Founders", 40, RoomType.LAB, Set.of())).getId();
	}

	private String instructor(String name) {
		String id = unique("I").replace(" ", "-");
		users.save(new AppUserEntity(id, name, null, "MATH"));
		return id;
	}

}
