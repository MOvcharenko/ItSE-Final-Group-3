package api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import core.CalendarDates;
import domain.ResourceStatus;
import domain.RoomType;
import integration.SisClient;
import jakarta.persistence.EntityManagerFactory;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import service.BookingService;
import service.CatalogService;
import service.ImportService;

/**
 * Calendars through the real API and database, with the SIS mocked. Terms are in September 2031, Monday the 1st to
 * Tuesday the 30th, and every check names its own term, so data from other tests cannot appear.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarApiIT {

	private static final String SCHEDULER = "U01";
	private static final String BIANCHI = "U02";
	private static final String STUDENT = "U03";
	private static final String MON_WED = """
			[{"pattern":"WEEKLY","day":"MONDAY","start":"10:00","end":"10:50"},
			 {"pattern":"WEEKLY","day":"WEDNESDAY","start":"10:00","end":"10:50"}]""";
	private static final String TUESDAY = """
			[{"pattern":"WEEKLY","day":"TUESDAY","start":"14:00","end":"15:15"}]""";

	@Autowired
	MockMvc mvc;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	RoomRepository rooms;
	@Autowired
	EquipmentRepository equipment;
	@Autowired
	AppUserRepository users;
	@Autowired
	Clock clock;
	@Autowired
	EntityManagerFactory entityManagers;
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

	private String termId;
	private String courseA;
	private String roomA;
	private String buildingA;
	private String sectionA;
	private String courseB;
	private String roomB;
	private String sectionB;
	private String otherInstructor;

	/** Section A: Mon and Wed, taught by Bianchi. Section B: Tue, taught by someone else, in another building. */
	@BeforeEach
	void twoSections() throws Exception {
		termId = term();
		courseA = unique("MATH");
		courseB = unique("CS");
		buildingA = unique("Founders");
		roomA = room(buildingA);
		roomB = room(unique("Hale"));
		otherInstructor = instructor("A. Novak");
		sectionA = place(termId, subject(courseA), roomA, BIANCHI, MON_WED);
		sectionB = place(termId, subject(courseB), roomB, otherInstructor, TUESDAY);
	}

	// ---- Views ----

	@Test
	@DisplayName("TC-FR6.1: the day view shows that day's meetings")
	void dayView() throws Exception {
		calendar(SCHEDULER, "view=DAY&date=2031-09-03&termId=" + termId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2031-09-03"))
			.andExpect(jsonPath("$.to").value("2031-09-03"))
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].courseCode").value(courseA))
			.andExpect(jsonPath("$.events[0].courseTitle").value("A course"))
			.andExpect(jsonPath("$.events[0].date").value("2031-09-03"))
			.andExpect(jsonPath("$.events[0].start").value(startsWith("10:00")))
			.andExpect(jsonPath("$.events[0].roomId").value(roomA))
			.andExpect(jsonPath("$.events[0].building").value(buildingA))
			.andExpect(jsonPath("$.events[0].instructors[0].id").value(BIANCHI))
			.andExpect(jsonPath("$.events[0].instructors[0].name").value("L. Bianchi"))
			.andExpect(jsonPath("$.events[0].pattern").value("WEEKLY"))
			.andExpect(jsonPath("$.enrollmentAvailable").value(true));
	}

	@Test
	@DisplayName("TC-FR6.1: the week view runs Monday to Sunday, in time order")
	void weekView() throws Exception {
		calendar(SCHEDULER, "view=WEEK&date=2031-09-10&termId=" + termId)
			.andExpect(jsonPath("$.from").value("2031-09-08"))
			.andExpect(jsonPath("$.to").value("2031-09-14"))
			.andExpect(jsonPath("$.events.length()").value(3))
			.andExpect(jsonPath("$.events[0].date").value("2031-09-08"))
			.andExpect(jsonPath("$.events[1].date").value("2031-09-09"))
			.andExpect(jsonPath("$.events[2].date").value("2031-09-10"));
	}

	@Test
	@DisplayName("TC-FR6.1: the term view covers the whole term")
	void termView() throws Exception {
		// Mondays 1, 8, 15, 22, 29; Wednesdays 3, 10, 17, 24; Tuesdays 2, 9, 16, 23, 30
		calendar(SCHEDULER, "view=TERM&termId=" + termId)
			.andExpect(jsonPath("$.from").value("2031-09-01"))
			.andExpect(jsonPath("$.to").value("2031-09-30"))
			.andExpect(jsonPath("$.events.length()").value(14));
	}

	@Test
	@DisplayName("TC-FR6.1: exams and one-time meetings appear only on their own date")
	void examInCalendar() throws Exception {
		String exam = """
				[{"pattern":"EXAM","date":"2031-09-18","start":"09:00","end":"11:00"}]""";
		String examCourse = unique("EXAM");
		place(termId, subject(examCourse), roomA, BIANCHI, exam);

		calendar(SCHEDULER, "view=DAY&date=2031-09-18&termId=" + termId + "&course=" + examCourse)
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].pattern").value("EXAM"));
		calendar(SCHEDULER, "view=DAY&date=2031-09-25&termId=" + termId + "&course=" + examCourse)
			.andExpect(jsonPath("$.events.length()").value(0));
	}

	@Test
	@DisplayName("TC-FR6.1: with no view or date, it shows the current week")
	void defaults() throws Exception {
		LocalDate today = LocalDate.now(clock);
		calendar(SCHEDULER, "termId=" + termId)
			.andExpect(jsonPath("$.scope").value("WEEK"))
			.andExpect(jsonPath("$.from").value(CalendarDates.weekStart(today).toString()))
			.andExpect(jsonPath("$.to").value(CalendarDates.weekEnd(today).toString()));
	}

	@Test
	@DisplayName("TC-FR6.1: a week view without a term covers every term in that week")
	void weekAcrossTerms() throws Exception {
		String firstCourse = unique("EARLY");
		String laterCourse = unique("LATE");
		place(createTerm("2032-02-02", "2032-02-29"), subject(firstCourse), roomA, BIANCHI, TUESDAY);
		place(createTerm("2032-02-02", "2032-02-29"), subject(laterCourse), roomA, BIANCHI, TUESDAY);

		String body = calendar(SCHEDULER, "view=WEEK&date=2032-02-10").andReturn().getResponse().getContentAsString();
		List<String> courses = JsonPath.read(body, "$.events[*].courseCode");

		assertThat(courses).contains(firstCourse, laterCourse);
	}

	@Test
	@DisplayName("A Draft copy of a Published term does not double the calendar or make it ask which term, unless chosen")
	void copiedTermStaysOutOfTheWay() throws Exception {
		String course = unique("COPIED");
		String published = createTerm("2033-03-01", "2033-03-31");
		place(published, subject(course), roomA, BIANCHI, TUESDAY);
		send(post("/api/terms/" + published + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		String copy = JsonPath.read(send(post("/api/terms/" + published + "/copy"), SCHEDULER,
				"{\"name\":\"" + unique("Copy") + "\"}").andReturn().getResponse().getContentAsString(), "$.id");

		calendar(SCHEDULER, "view=WEEK&date=2033-03-08&course=" + course)
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].termId").value(published));
		calendar(SCHEDULER, "view=TERM&date=2033-03-08&course=" + course)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.events[0].termId").value(published));
		String ics = mvcBody(send(get("/api/calendar.ics?date=2033-03-08&course=" + course), SCHEDULER, null)
			.andExpect(status().isOk()));
		assertThat(eventIds(ics)).hasSize(1);
		assertThat(ics).contains("STATUS:CONFIRMED");

		calendar(SCHEDULER, "view=WEEK&date=2033-03-08&course=" + course + "&termId=" + copy)
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].termId").value(copy));
	}

	@Test
	@DisplayName("Two Published terms on the same date still need a choice")
	void twoPublishedTermsNeedAChoice() throws Exception {
		for (int i = 0; i < 2; i++) {
			String term = createTerm("2034-03-01", "2034-03-31");
			send(post("/api/terms/" + term + "/publish"), SCHEDULER, null).andExpect(status().isOk());
		}

		calendar(SCHEDULER, "view=TERM&date=2034-03-15")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("More than one term includes 2034-03-15. Choose a term."));
	}

	@Test
	@DisplayName("A term view with no term chosen uses the only term on that date, or asks")
	void termViewNeedsOneTerm() throws Exception {
		calendar(SCHEDULER, "view=TERM&date=2040-01-01")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("No term includes 2040-01-01."));
		createTerm("2042-03-01", "2042-03-31");
		createTerm("2042-03-01", "2042-03-31");
		calendar(SCHEDULER, "view=TERM&date=2042-03-10")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("More than one term includes 2042-03-10. Choose a term."));

		createTerm("2041-03-01", "2041-03-31");
		calendar(SCHEDULER, "view=TERM&date=2041-03-15")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value("2041-03-01"));
	}

	@Test
	@DisplayName("A bad view or date is refused")
	void badParameters() throws Exception {
		calendar(SCHEDULER, "view=MONTH").andExpect(status().isBadRequest());
		calendar(SCHEDULER, "date=soon").andExpect(status().isBadRequest());
		calendar(SCHEDULER, "termId=nope").andExpect(status().isNotFound());
	}

	// ---- Filters ----

	@Test
	@DisplayName("TC-FR6.2: filter by course, ignoring case")
	void filterCourse() throws Exception {
		assertThat(sectionsSeen(SCHEDULER, "course=" + courseA.toLowerCase())).containsOnly(sectionA);
	}

	@Test
	@DisplayName("TC-FR6.2: filter by instructor")
	void filterInstructor() throws Exception {
		assertThat(sectionsSeen(SCHEDULER, "instructor=" + otherInstructor)).containsOnly(sectionB);
	}

	@Test
	@DisplayName("TC-FR6.2: filter by room")
	void filterRoom() throws Exception {
		assertThat(sectionsSeen(SCHEDULER, "room=" + roomB)).containsOnly(sectionB);
	}

	@Test
	@DisplayName("TC-FR6.2: filter by building, ignoring case")
	void filterBuilding() throws Exception {
		assertThat(sectionsSeen(SCHEDULER, "building=" + buildingA.toUpperCase())).containsOnly(sectionA);
	}

	@Test
	@DisplayName("TC-FR6.2: filters combine, and no match gives an empty calendar")
	void filtersCombine() throws Exception {
		assertThat(sectionsSeen(SCHEDULER, "course=" + courseA + "&room=" + roomA)).containsOnly(sectionA);
		assertThat(sectionsSeen(SCHEDULER, "course=" + courseA + "&room=" + roomB)).isEmpty();
		assertThat(sectionsSeen(SCHEDULER, "course=nothing")).isEmpty();
	}

	@Test
	@DisplayName("TC-FR6.2: blank filters, as a web form sends them, mean no filter")
	void blankFilters() throws Exception {
		List<String> unfiltered = sectionsSeen(SCHEDULER, "");

		assertThat(unfiltered).contains(sectionA, sectionB);
		assertThat(sectionsSeen(SCHEDULER, "course=&instructor=&room=&building=")).isEqualTo(unfiltered);
		assertThat(sectionsSeen(SCHEDULER, "course= &building=  ")).isEqualTo(unfiltered);
		assertThat(sectionsSeen(SCHEDULER, "course=&room=" + roomB)).containsOnly(sectionB);
	}

	@Test
	@DisplayName("TC-FR6.2: a blank term, view or date means none was chosen")
	void blankTermViewAndDate() throws Exception {
		String section = place(createTerm("2032-03-01", "2032-03-31"), subject(unique("BLANK")), roomA, BIANCHI, TUESDAY);
		String body = calendar(SCHEDULER, "view=WEEK&date=2032-03-10&termId=").andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<String> seen = JsonPath.read(body, "$.events[*].sectionId");
		assertThat(seen).contains(section);

		LocalDate today = LocalDate.now(clock);
		calendar(SCHEDULER, "view=&date=&termId=" + termId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scope").value("WEEK"))
			.andExpect(jsonPath("$.from").value(CalendarDates.weekStart(today).toString()));
	}

	@Test
	@DisplayName("TC-FR6.2: blank filters on the export mean no filter")
	void blankFiltersOnExport() throws Exception {
		assertThat(unfolded(ics(SCHEDULER, "course=&instructor=&room=&building="))).contains(sectionA, sectionB);
		send(get("/api/calendar.ics?termId=&date=2040-01-01"), SCHEDULER, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("No term includes 2040-01-01."));
	}

	// ---- Who sees what ----

	@Test
	@DisplayName("TC-TR3.2: a student sees only the sections they are enrolled in")
	void studentSeesOwn() throws Exception {
		publish();
		when(sis.enrolledSectionIds(STUDENT)).thenReturn(Set.of(sectionB));

		assertThat(sectionsSeen(STUDENT, "")).containsOnly(sectionB);
	}

	@Test
	@DisplayName("TC-TR3.2: a filter cannot widen what a student sees")
	void studentFilterCannotWiden() throws Exception {
		publish();
		when(sis.enrolledSectionIds(STUDENT)).thenReturn(Set.of(sectionB));

		assertThat(sectionsSeen(STUDENT, "course=" + courseA)).isEmpty();
		assertThat(sectionsSeen(STUDENT, "instructor=" + BIANCHI)).isEmpty();
	}

	@Test
	@DisplayName("TC-TR3.2: a student with no enrollment sees nothing")
	void studentNotEnrolled() throws Exception {
		publish();
		when(sis.enrolledSectionIds(STUDENT)).thenReturn(Set.of());

		assertThat(sectionsSeen(STUDENT, "")).isEmpty();
	}

	@Test
	@DisplayName("TC-TR3.2: a Draft is hidden from students and instructors, even for their own sections")
	void draftHidden() throws Exception {
		when(sis.enrolledSectionIds(STUDENT)).thenReturn(Set.of(sectionA));

		calendar(STUDENT, "view=TERM&termId=" + termId).andExpect(status().isNotFound());
		calendar(BIANCHI, "view=TERM&termId=" + termId).andExpect(status().isNotFound());

		String body = calendar(STUDENT, "view=WEEK&date=2031-09-10").andReturn().getResponse().getContentAsString();
		List<String> seen = JsonPath.read(body, "$.events[*].sectionId");
		assertThat(seen).doesNotContain(sectionA, sectionB);

		body = calendar(BIANCHI, "view=WEEK&date=2031-09-10").andReturn().getResponse().getContentAsString();
		seen = JsonPath.read(body, "$.events[*].sectionId");
		assertThat(seen).doesNotContain(sectionA, sectionB);
	}

	@Test
	@DisplayName("A Scheduler sees Drafts, marked as Drafts")
	void schedulerSeesDraft() throws Exception {
		calendar(SCHEDULER, "view=DAY&date=2031-09-03&termId=" + termId)
			.andExpect(jsonPath("$.events[0].termStatus").value("DRAFT"));
	}

	@Test
	@DisplayName("TC-TR3.3: an instructor sees only what they teach, and a filter cannot widen it")
	void instructorSeesOwn() throws Exception {
		publish();

		assertThat(sectionsSeen(BIANCHI, "")).containsOnly(sectionA);
		assertThat(sectionsSeen(BIANCHI, "instructor=" + otherInstructor)).isEmpty();
		assertThat(sectionsSeen(BIANCHI, "room=" + roomB)).isEmpty();
	}

	@Test
	@DisplayName("A co-instructor sees the section too")
	void coInstructor() throws Exception {
		String shared = place(termId, subject(unique("SHARED")), roomA, BIANCHI + "\",\"" + otherInstructor, TUESDAY);
		// Novak now teaches two Tuesday sections at once, a High conflict, so publishing needs the confirmation (FR4.7).
		publishAnyway(termId);

		assertThat(sectionsSeen(BIANCHI, "")).contains(shared);
	}

	@Test
	@DisplayName("UC-06 3a: with the SIS down, a student's calendar says enrollment is unavailable instead of looking empty")
	void sisDown() throws Exception {
		publish();
		doThrow(new IllegalStateException("SIS unavailable")).when(sis).enrolledSectionIds(anyString());

		calendar(STUDENT, "view=TERM&termId=" + termId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enrollmentAvailable").value(false))
			.andExpect(jsonPath("$.events.length()").value(0));
		calendar(BIANCHI, "view=TERM&termId=" + termId)
			.andExpect(jsonPath("$.enrollmentAvailable").value(true))
			.andExpect(jsonPath("$.events[0].sectionId").value(sectionA));
	}

	@Test
	@DisplayName("A section with no room still appears, and a room or building filter leaves it out")
	void noRoom() throws Exception {
		RoomEntity room = rooms.findById(roomA).orElseThrow();
		room.setStatus(ResourceStatus.MAINTENANCE);
		rooms.save(room);
		String copy = JsonPath.read(send(post("/api/terms/" + termId + "/copy"), SCHEDULER,
				"{\"name\":\"" + unique("Copy") + "\"}").andReturn().getResponse().getContentAsString(), "$.id");

		calendar(SCHEDULER, "view=DAY&date=2031-09-03&termId=" + copy)
			.andExpect(jsonPath("$.events.length()").value(1))
			.andExpect(jsonPath("$.events[0].roomId").doesNotExist());
		calendar(SCHEDULER, "view=DAY&date=2031-09-03&building=" + buildingA + "&termId=" + copy)
			.andExpect(jsonPath("$.events.length()").value(0));
	}

	@Test
	@DisplayName("An instructor's calendar in a term of many sections takes a handful of queries, not one per section")
	void queryCount() throws Exception {
		String bigTerm = term();
		for (int n = 0; n < 60; n++) {
			place(bigTerm, subject(unique("N")), roomA, n == 0 ? BIANCHI : otherInstructor, TUESDAY);
		}
		// 60 sections in one room at one time are High conflicts, so publishing needs the confirmation (FR4.7).
		publishAnyway(bigTerm);
		Statistics statistics = entityManagers.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		calendar(BIANCHI, "view=WEEK&date=2031-09-10&termId=" + bigTerm)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.events.length()").value(1));

		long queries = statistics.getPrepareStatementCount();
		statistics.setStatisticsEnabled(false);
		assertThat(queries).isLessThanOrEqualTo(10);
	}

	// ---- .ics export ----

	@Test
	@DisplayName("TC-FR6.3: the export is a downloadable calendar file")
	void icsResponse() throws Exception {
		send(get("/api/calendar.ics?termId=" + termId), SCHEDULER, null)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/calendar"))
			.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"calendar.ics\""));
	}

	@Test
	@DisplayName("TC-FR6.3: each weekly meeting is one repeating event from its first date in the term")
	void icsWeekly() throws Exception {
		String ics = unfolded(ics(SCHEDULER, "course=" + courseA));

		assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(3);
		assertThat(ics)
			.contains("DTSTART:20310901T100000\r\nDTEND:20310901T105000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20310930T235959")
			.contains("DTSTART:20310903T100000\r\nDTEND:20310903T105000\r\nRRULE:FREQ=WEEKLY;BYDAY=WE;UNTIL=20310930T235959")
			.contains("SUMMARY:" + courseA + " section 01")
			.contains("LOCATION:")
			.contains(buildingA)
			.contains("DESCRIPTION:A course\\nInstructors: L. Bianchi\\nTerm: ");
	}

	@Test
	@DisplayName("TC-FR6.3: an exam is a single event, labelled as an exam")
	void icsExam() throws Exception {
		String examCourse = unique("EXAM");
		place(termId, subject(examCourse), roomA, BIANCHI,
				"[{\"pattern\":\"EXAM\",\"date\":\"2031-09-18\",\"start\":\"09:00\",\"end\":\"11:00\"}]");

		String ics = unfolded(ics(SCHEDULER, "course=" + examCourse));

		assertThat(ics).contains("DTSTART:20310918T090000").contains("SUMMARY:" + examCourse + " section 01 (Exam)")
			.doesNotContain("RRULE");
	}

	@Test
	@DisplayName("TC-FR6.3: a weekly meeting whose day never falls in the term is left out")
	void icsDayOutsideShortTerm() throws Exception {
		String shortTerm = createTerm("2031-09-01", "2031-09-02");
		String course = unique("SHORT");
		place(shortTerm, subject(course), roomA, BIANCHI, MON_WED);

		String ics = unfolded(mvcBody(send(get("/api/calendar.ics?termId=" + shortTerm), SCHEDULER, null)));

		assertThat(ics.split("BEGIN:VEVENT", -1)).hasSize(2);
		assertThat(ics).contains("BYDAY=MO");
	}

	@Test
	@DisplayName("TC-FR6.3: every meeting has its own event ID, even with the same date and start")
	void icsIdsAreUnique() throws Exception {
		String course = unique("SAME");
		place(termId, subject(course), roomA, BIANCHI, """
				[{"pattern":"ONE_TIME","date":"2031-09-18","start":"09:00","end":"10:00"},
				 {"pattern":"EXAM","date":"2031-09-18","start":"09:00","end":"10:00"},
				 {"pattern":"EXAM","date":"2031-09-18","start":"09:00","end":"11:00"}]""");

		assertThat(eventIds(ics(SCHEDULER, "course=" + course))).hasSize(3).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("TC-FR6.3: event IDs stay the same between exports, so importing again updates rather than duplicates")
	void icsIdsAreStable() throws Exception {
		assertThat(eventIds(ics(SCHEDULER, ""))).isEqualTo(eventIds(ics(SCHEDULER, "")));
	}

	@Test
	@DisplayName("TC-FR6.3: moving a meeting keeps its event ID and raises its version, so importing again can update it")
	void icsRescheduled() throws Exception {
		String before = ics(SCHEDULER, "course=" + courseA);
		assertThat(unfolded(before)).contains("SEQUENCE:0");

		editSectionA("""
				[{"pattern":"WEEKLY","day":"MONDAY","start":"11:00","end":"11:50"},
				 {"pattern":"WEEKLY","day":"WEDNESDAY","start":"10:00","end":"10:50"}]""", "");

		String after = unfolded(ics(SCHEDULER, "course=" + courseA));
		assertThat(eventIds(after)).isEqualTo(eventIds(before));
		assertThat(after).contains("DTSTART:20310901T110000").doesNotContain("DTSTART:20310901T100000")
			.contains("SEQUENCE:1").doesNotContain("SEQUENCE:0");
	}

	@Test
	@DisplayName("TC-FR6.3: moving a meeting to another day updates the events without adding any")
	void icsMovedToAnotherDay() throws Exception {
		List<String> before = eventIds(ics(SCHEDULER, "course=" + courseA));

		editSectionA("""
				[{"pattern":"WEEKLY","day":"WEDNESDAY","start":"10:00","end":"10:50"},
				 {"pattern":"WEEKLY","day":"THURSDAY","start":"10:00","end":"10:50"}]""", "");

		String after = unfolded(ics(SCHEDULER, "course=" + courseA));
		assertThat(eventIds(after)).isEqualTo(before);
		assertThat(after).contains("BYDAY=WE").contains("BYDAY=TH").doesNotContain("BYDAY=MO");
	}

	@Test
	@DisplayName("TC-FR6.3: adding an exam leaves the event IDs of the weekly meetings alone")
	void icsExamAdded() throws Exception {
		List<String> before = eventIds(ics(SCHEDULER, "course=" + courseA));

		editSectionA("""
				[{"pattern":"WEEKLY","day":"MONDAY","start":"10:00","end":"10:50"},
				 {"pattern":"WEEKLY","day":"WEDNESDAY","start":"10:00","end":"10:50"},
				 {"pattern":"EXAM","date":"2031-09-18","start":"09:00","end":"11:00"}]""", "");

		assertThat(eventIds(ics(SCHEDULER, "course=" + courseA))).hasSize(3).containsAll(before);
	}

	@Test
	@DisplayName("TC-FR6.3: an equipment-only edit changes nothing in the file, so the version stays the same")
	void icsEquipmentOnly() throws Exception {
		String projector = equipment.save(EquipmentEntity.create(unique("Projector"), "Projector", 3, null)).getId();

		editSectionA(MON_WED, ",\"equipment\":[{\"equipmentId\":\"" + projector + "\",\"units\":1}]");

		assertThat(unfolded(ics(SCHEDULER, "course=" + courseA))).contains("SEQUENCE:0");
	}

	@Test
	@DisplayName("TC-FR6.3: filters narrow the export")
	void icsFilters() throws Exception {
		assertThat(ics(SCHEDULER, "building=" + buildingA)).contains(sectionA).doesNotContain(sectionB);
		assertThat(ics(SCHEDULER, "course=nothing")).doesNotContain("BEGIN:VEVENT");
	}

	@Test
	@DisplayName("TC-TR3.2: a student's export has only their enrolled sections")
	void icsStudent() throws Exception {
		publish();
		when(sis.enrolledSectionIds(STUDENT)).thenReturn(Set.of(sectionB));

		assertThat(unfolded(ics(STUDENT, ""))).contains(sectionB).doesNotContain(sectionA);
		assertThat(unfolded(ics(STUDENT, "course=" + courseA))).doesNotContain("BEGIN:VEVENT");
	}

	@Test
	@DisplayName("TC-TR3.3: an instructor's export has only what they teach")
	void icsInstructor() throws Exception {
		publish();

		assertThat(unfolded(ics(BIANCHI, ""))).contains(sectionA).doesNotContain(sectionB);
	}

	@Test
	@DisplayName("TC-TR3.2: a Draft cannot be exported by a student or instructor; for a Scheduler it is tentative")
	void icsDraft() throws Exception {
		send(get("/api/calendar.ics?termId=" + termId), STUDENT, null).andExpect(status().isNotFound());
		send(get("/api/calendar.ics?termId=" + termId), BIANCHI, null).andExpect(status().isNotFound());

		assertThat(ics(SCHEDULER, "")).contains("STATUS:TENTATIVE").doesNotContain("STATUS:CONFIRMED");

		publish();
		assertThat(ics(SCHEDULER, "")).contains("STATUS:CONFIRMED").doesNotContain("STATUS:TENTATIVE");
	}

	@Test
	@DisplayName("UC-06 3a: with the SIS down, a student's export is refused rather than looking empty")
	void icsSisDown() throws Exception {
		publish();
		doThrow(new IllegalStateException("SIS unavailable")).when(sis).enrolledSectionIds(anyString());

		send(get("/api/calendar.ics?termId=" + termId), STUDENT, null)
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value("Your enrolled sections could not be loaded. Please try again shortly."));
		assertThat(unfolded(ics(BIANCHI, ""))).contains(sectionA);
	}

	@Test
	@DisplayName("A student's calendar and export ask the SIS without holding a database connection")
	void sisCalledWithoutAConnection() throws Exception {
		publish();
		AtomicInteger connectionsInUse = new AtomicInteger(-1);
		when(sis.enrolledSectionIds(STUDENT)).thenAnswer(call -> {
			connectionsInUse.set(dataSource.getHikariPoolMXBean().getActiveConnections());
			return Set.of(sectionA);
		});

		calendar(STUDENT, "view=TERM&termId=" + termId).andExpect(status().isOk());
		assertThat(connectionsInUse).hasValue(0);

		connectionsInUse.set(-1);
		assertThat(unfolded(ics(STUDENT, ""))).contains(sectionA);
		assertThat(connectionsInUse).hasValue(0);
	}

	@Test
	@DisplayName("UC-06 3a: a SIS failure inside its own transaction still counts as the SIS being down, not a server error")
	void sisFailsInsideATransaction() throws Exception {
		publish();
		when(sis.enrolledSectionIds(STUDENT)).thenAnswer(call -> new TransactionTemplate(transactions).execute(status -> {
			throw new IllegalStateException("SIS query failed");
		}));

		calendar(STUDENT, "view=TERM&termId=" + termId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.enrollmentAvailable").value(false))
			.andExpect(jsonPath("$.events.length()").value(0));
		send(get("/api/calendar.ics?termId=" + termId), STUDENT, null).andExpect(status().isServiceUnavailable());
	}

	@Test
	@DisplayName("An export with no term chosen needs exactly one term on the date, and errors still explain themselves")
	void icsTermChoice() throws Exception {
		send(get("/api/calendar.ics?date=2040-01-01"), SCHEDULER, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.detail").value("No term includes 2040-01-01."));
		send(get("/api/calendar.ics?termId=nope").accept("text/calendar"), SCHEDULER, null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Term nope was not found."));
	}

	// ---- Helpers ----

	private void editSectionA(String meetings, String extraFields) throws Exception {
		send(put("/api/terms/" + termId + "/sections/" + sectionA), SCHEDULER, "{\"roomId\":\"" + roomA
				+ "\",\"instructorIds\":[\"" + BIANCHI + "\"],\"meetings\":" + meetings + extraFields + "}")
			.andExpect(status().isOk());
	}

	private String ics(String user, String filters) throws Exception {
		return mvcBody(send(get("/api/calendar.ics?termId=" + termId + (filters.isEmpty() ? "" : "&" + filters)), user, null)
			.andExpect(status().isOk()));
	}

	private static String mvcBody(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private static List<String> eventIds(String ics) {
		return unfolded(ics).lines().filter(line -> line.startsWith("UID:")).toList();
	}

	private static String unfolded(String ics) {
		return ics.replace("\r\n ", "");
	}

	private List<String> sectionsSeen(String user, String filters) throws Exception {
		String body = calendar(user, "view=TERM&termId=" + termId + (filters.isEmpty() ? "" : "&" + filters))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.events[*].sectionId");
	}

	private ResultActions calendar(String user, String query) throws Exception {
		return send(get("/api/calendar?" + query), user, null);
	}

	private void publish() throws Exception {
		send(post("/api/terms/" + termId + "/publish"), SCHEDULER, null).andExpect(status().isOk());
	}

	private void publishAnyway(String term) throws Exception {
		send(post("/api/terms/" + term + "/publish?confirm=true"), SCHEDULER, null).andExpect(status().isOk());
	}

	private String term() throws Exception {
		return createTerm("2031-09-01", "2031-09-30");
	}

	private String createTerm(String start, String end) throws Exception {
		String body = send(post("/api/terms"), SCHEDULER, "{\"name\":\"" + unique("Term") + "\",\"startDate\":\"" + start
				+ "\",\"endDate\":\"" + end + "\"}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private String place(String term, String subject, String room, String instructors, String meetings) throws Exception {
		String body = send(post("/api/terms/" + term + "/sections"), SCHEDULER, "{\"subjectId\":\"" + subject
				+ "\",\"roomId\":\"" + room + "\",\"instructorIds\":[\"" + instructors + "\"],\"meetings\":" + meetings + "}")
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
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

	private static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private String subject(String courseCode) {
		return subjects.save(SubjectEntity.create(courseCode, "A course", 3, RoomType.LAB, "MATH", Set.of())).getId();
	}

	private String room(String building) {
		return rooms.save(RoomEntity.create(unique("Room"), building, 40, RoomType.LAB, Set.of())).getId();
	}

	private String instructor(String name) {
		String id = unique("I");
		users.save(new AppUserEntity(id, name, null, "MATH"));
		return id;
	}

}
