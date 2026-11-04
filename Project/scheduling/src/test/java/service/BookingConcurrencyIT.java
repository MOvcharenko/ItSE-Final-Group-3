package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import app.SchedulingApplication;
import domain.Role;
import domain.RoomType;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import security.CurrentUser;
import security.CurrentUserProvider;

/**
 * NFR3.1 / TC-FR2.3b against the real database and the real transaction + lock. Deliberately NOT @Transactional:
 * a test-wide transaction would put both "users" in one transaction and hide the race, and the data must be
 * committed before the two threads start.
 *
 * The real provider reads the signed-in user from the calling thread, which the worker threads do not have, so the
 * approver is mocked. Notices are mocked because a saved notice would stop the test removing its bookings. It leaves
 * nothing behind: it removes its own rows, and only creates the approver's profile row if missing.
 * The same algorithm was run against H2 and PostgreSQL 16 with plain JDBC (40 rounds each, with and without the
 * lock): without the lock every round double-booked, with it every round had exactly one winner.
 */
@SpringBootTest(classes = SchedulingApplication.class)
@ActiveProfiles("dev")
class BookingConcurrencyIT {

	private static final LocalDateTime FIVE_PM = LocalDateTime.of(2026, 11, 10, 17, 0);
	private static final LocalDateTime SIX_THIRTY = LocalDateTime.of(2026, 11, 10, 18, 30);

	@Autowired
	BookingService bookingService;
	@Autowired
	BookingRepository bookings;
	@Autowired
	RoomRepository rooms;
	@Autowired
	AppUserRepository users;

	@MockitoBean
	CurrentUserProvider currentUser;
	@MockitoBean
	NoticeService notices;

	@RepeatedTest(5)
	@DisplayName("TC-FR2.3b / NFR3.1: two overlapping approvals at the same instant: exactly one wins, the other is refused")
	void exactlyOneOfTwoOverlappingApprovalsWins() throws Exception {
		when(currentUser.get()).thenReturn(new CurrentUser("U01", "Maya Brooks", Role.SCHEDULER, Set.of("CS", "MATH")));
		String tag = UUID.randomUUID().toString().substring(0, 8);
		// the approver's profile row is created up front so the two threads do not race to insert it
		users.findById("U01").orElseGet(() -> users.save(new AppUserEntity("U01", "Maya Brooks", null, null)));
		AppUserEntity requester = users.save(new AppUserEntity("IT-" + tag, "Concurrent requester", null, null));
		RoomEntity room = rooms.save(RoomEntity.create("IT room " + tag, "IT", 10, RoomType.LAB, Set.of()));
		LocalDateTime created = LocalDateTime.of(2026, 10, 24, 9, 0);
		BookingEntity first = bookings.save(BookingEntity.forRoom(requester, room, FIVE_PM, SIX_THIRTY, "first", created));
		BookingEntity second = bookings.save(BookingEntity.forRoom(requester, room, FIVE_PM.plusMinutes(30),
				SIX_THIRTY.plusMinutes(30), "overlaps the first", created));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Throwable>> results = new ArrayList<>();
			for (BookingEntity b : List.of(first, second)) {
				Callable<Throwable> approve = () -> {
					go.await();
					try {
						bookingService.approve(b.getId());
						return null;
					} catch (Throwable t) {
						return t;
					}
				};
				results.add(pool.submit(approve));
			}
			go.countDown();

			List<Throwable> failures = new ArrayList<>();
			for (Future<Throwable> f : results) {
				Throwable t = f.get(30, TimeUnit.SECONDS);
				if (t != null) {
					failures.add(t);
				}
			}

			assertEquals(1, failures.size(), "exactly one approval must be refused: " + failures);
			assertInstanceOf(BookingCollisionException.class, failures.get(0));
			// the database agrees: only one approved booking holds this room in the shared window
			assertEquals(1, bookings.countApprovedForRoom(room.getId(), FIVE_PM.plusMinutes(30), SIX_THIRTY));
		} finally {
			pool.shutdownNow();
			bookings.deleteAll(List.of(first, second));
			rooms.delete(room);
			users.delete(requester);
		}
	}
}
