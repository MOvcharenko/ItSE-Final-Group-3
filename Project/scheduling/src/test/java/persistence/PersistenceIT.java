package persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import app.SchedulingApplication;
import domain.BookingStatus;
import domain.ImportType;
import domain.RoomType;
import domain.RowOutcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Runs the repositories against the real Flyway schema on H2. Every test rolls back, so nothing leaks between tests.
 * NOT run when this file was written (no Spring jars were available): if the context fails to start because the
 * security configuration is not wired yet, temporarily switch to a @DataJpaTest slice (its package changed in
 * Spring Boot 4: use the IDE's import) with the same body.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@ActiveProfiles("dev")
@Transactional
class PersistenceIT {

	private static final LocalDateTime FIVE_PM = LocalDateTime.of(2026, 10, 14, 17, 0);
	private static final LocalDateTime SIX_THIRTY = LocalDateTime.of(2026, 10, 14, 18, 30);

	@Autowired
	AppUserRepository users;
	@Autowired
	RoomRepository rooms;
	@Autowired
	EquipmentRepository equipment;
	@Autowired
	SubjectRepository subjects;
	@Autowired
	BookingRepository bookings;
	@Autowired
	ImportBatchRepository batches;
	@PersistenceContext
	EntityManager em;

	private AppUserEntity requester;

	@BeforeEach
	void user() {
		requester = users.save(new AppUserEntity("IT-U1", "L. Bianchi", "l.bianchi@univ.edu", "MATH"));
	}

	private RoomEntity founders110() {
		return rooms.save(RoomEntity.create("Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of("Projector")));
	}

	private BookingEntity approvedRoomBooking(RoomEntity room, LocalDateTime start, LocalDateTime end) {
		BookingEntity b = BookingEntity.forRoom(requester, room, start, end, "Guest talk", FIVE_PM.minusDays(10));
		b.markApproved("IT-U1", FIVE_PM.minusDays(9));
		return bookings.save(b);
	}

	@Test
	@DisplayName("TC-FR3.2: a room and its fixed equipment survive a round trip")
	void roomRoundTrip() {
		RoomEntity saved = founders110();
		em.flush();
		em.clear();

		RoomEntity loaded = rooms.findByNameIgnoreCase("founders 110").orElseThrow();
		assertEquals(saved.getId(), loaded.getId());
		assertEquals(Set.of("Projector"), loaded.getFixedEquipment());
		assertEquals(60, loaded.getCapacity());
	}

	@Test
	@DisplayName("Duplicate room name is refused by the database (UNIQUE), not only by the service")
	void duplicateRoomNameIsRefused() {
		rooms.saveAndFlush(RoomEntity.create("Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of()));
		assertThrows(DataIntegrityViolationException.class, () -> rooms.saveAndFlush(
				RoomEntity.create("Founders 110", "Other", 10, RoomType.LAB, Set.of())));
	}

	@Test
	@DisplayName("TC-FR2.4 boundary: a window that starts when an approved booking ends is NOT blocked; one minute earlier is")
	void touchingWindowsDoNotOverlap() {
		RoomEntity room = founders110();
		approvedRoomBooking(room, FIVE_PM, SIX_THIRTY);
		em.flush();

		assertEquals(0, bookings.countApprovedForRoom(room.getId(), SIX_THIRTY, SIX_THIRTY.plusHours(1)));
		assertEquals(1, bookings.countApprovedForRoom(room.getId(), SIX_THIRTY.minusMinutes(1), SIX_THIRTY.plusHours(1)));
		assertEquals(1, bookings.countApprovedForRoom(room.getId(), FIVE_PM.plusMinutes(30), FIVE_PM.plusHours(1)));
		assertEquals(0, bookings.countApprovedForRoom(room.getId(), FIVE_PM.minusHours(1), FIVE_PM));
	}

	@Test
	@DisplayName("TC-FR2.3b: only APPROVED bookings reserve a room; a PENDING one does not")
	void pendingBookingsDoNotReserve() {
		RoomEntity room = founders110();
		bookings.save(BookingEntity.forRoom(requester, room, FIVE_PM, SIX_THIRTY, "pending", FIVE_PM.minusDays(1)));
		em.flush();

		assertEquals(0, bookings.countApprovedForRoom(room.getId(), FIVE_PM, SIX_THIRTY));
		assertEquals(1, bookings.countForRoomInWindow(room.getId(), BookingStatus.PENDING, FIVE_PM, SIX_THIRTY));
	}

	@Test
	@DisplayName("FR2.4: busyRoomIds lists rooms with an approved booking in the window")
	void busyRoomsInWindow() {
		RoomEntity busy = founders110();
		RoomEntity free = rooms.save(RoomEntity.create("Hale Hall 204", "Hale", 120, RoomType.LECTURE_HALL, Set.of()));
		approvedRoomBooking(busy, FIVE_PM, SIX_THIRTY);
		em.flush();

		List<String> ids = bookings.busyRoomIds(FIVE_PM, SIX_THIRTY);
		assertTrue(ids.contains(busy.getId()));
		assertTrue(!ids.contains(free.getId()));
	}

	@Test
	@DisplayName("FR2.4 / OI-1: equipment usage counts approved bookings per item, compared with unit_count")
	void equipmentUsagePerItem() {
		EquipmentEntity speaker = equipment.save(EquipmentEntity.create("Portable speaker", "Speaker", 3, null));
		for (int i = 0; i < 2; i++) {
			BookingEntity b = BookingEntity.forEquipment(requester, speaker, FIVE_PM, SIX_THIRTY, "talk " + i, FIVE_PM.minusDays(1));
			b.markApproved("IT-U1", FIVE_PM.minusHours(1));
			bookings.save(b);
		}
		em.flush();

		var usage = bookings.approvedEquipmentUsage(FIVE_PM, SIX_THIRTY);
		assertEquals(1, usage.size());
		assertEquals(speaker.getId(), usage.get(0).getEquipmentId());
		assertEquals(2, usage.get(0).getUsed());
		assertEquals(1, speaker.getUnitCount() - usage.get(0).getUsed()); // one unit still free
	}

	@Test
	@DisplayName("NFR3.1: the resource row can be locked inside a transaction")
	void lockByIdReturnsTheRow() {
		RoomEntity room = founders110();
		em.flush();
		assertEquals(room.getId(), rooms.lockById(room.getId()).orElseThrow().getId());
		assertTrue(rooms.lockById("no-such-room").isEmpty());
	}

	@Test
	@DisplayName("TR4.5: the queue is fetched with requester and resource in one query (no lazy loading later)")
	void queueIsFetchedEagerly() {
		RoomEntity room = founders110();
		EquipmentEntity speaker = equipment.save(EquipmentEntity.create("Portable speaker", "Speaker", 3, null));
		bookings.save(BookingEntity.forRoom(requester, room, FIVE_PM, SIX_THIRTY, "room", FIVE_PM.minusDays(2)));
		bookings.save(BookingEntity.forEquipment(requester, speaker, FIVE_PM, SIX_THIRTY, "speaker", FIVE_PM.minusDays(1)));
		em.flush();
		em.clear();

		List<BookingEntity> queue = bookings.findQueueForRequester("IT-U1");
		assertEquals(2, queue.size());
		assertEquals("room", queue.get(0).getPurpose()); // oldest first
		assertTrue(Hibernate.isInitialized(queue.get(0).getRequester()));
		assertTrue(Hibernate.isInitialized(queue.get(0).getRoom()));
		assertEquals("Founders 110", queue.get(0).resourceName());
		assertEquals("Portable speaker", queue.get(1).resourceName());
		assertEquals(0, bookings.findQueueForRequester("somebody-else").size());
	}

	@Test
	@DisplayName("TC-FR3.6: an import batch keeps every row's outcome and reason, in row order")
	void importBatchRoundTrip() {
		ImportBatchEntity batch = ImportBatchEntity.create("faulty.csv", ImportType.READY_MADE_SCHEDULE,
				FIVE_PM, "IT-U1");
		batch.addRow(2, RowOutcome.NEEDS_REVIEW, "room not recognized");
		batch.addRow(1, RowOutcome.ADDED, null);
		batches.save(batch);
		em.flush();
		em.clear();

		ImportBatchEntity loaded = batches.findById(batch.getId()).orElseThrow();
		assertEquals(List.of(1, 2), loaded.getRows().stream().map(ImportRowRecord::getRowNo).toList());
		assertEquals("room not recognized", loaded.getRows().get(1).getReason());
		assertEquals(1, loaded.countByOutcome(RowOutcome.NEEDS_REVIEW));
	}

	@Test
	@DisplayName("TC-FR3.1: a new subject is 'Not yet scheduled' (no placed section) and department is optional")
	void newSubjectIsNotScheduled() {
		SubjectEntity psy = subjects.save(SubjectEntity.create("PSY 220", "Cognitive Psychology", 3,
				RoomType.LECTURE_HALL, null, Set.of()));
		em.flush();
		assertTrue(!subjects.scheduledSubjectIds().contains(psy.getId()));
		assertTrue(subjects.existsByCourseCodeIgnoreCase("psy 220"));
	}

	@Test
	@DisplayName("UC-12 6a: a duplicate course code is refused by the database")
	void duplicateCourseCodeIsRefused() {
		subjects.saveAndFlush(SubjectEntity.create("PSY 220", "A", 3, RoomType.LAB, null, Set.of()));
		assertThrows(DataIntegrityViolationException.class,
				() -> subjects.saveAndFlush(SubjectEntity.create("PSY 220", "B", 3, RoomType.LAB, null, Set.of())));
	}
}
