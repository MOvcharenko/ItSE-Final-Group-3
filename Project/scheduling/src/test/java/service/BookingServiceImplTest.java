package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import domain.BookingStatus;
import domain.NoticeEvent;
import domain.NoticeEvent;
import domain.ResourceStatus;
import domain.ResourceType;
import domain.RoomType;
import jakarta.persistence.EntityManager;
import persistence.AppUserEntity;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import security.CurrentUser;
import service.view.BookingView;
import service.view.EquipmentView;
import service.view.RoomView;

class BookingServiceImplTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 10, 0);
	static final LocalDateTime FIVE_PM = LocalDateTime.of(2026, 11, 10, 17, 0);
	static final LocalDateTime SIX_THIRTY = LocalDateTime.of(2026, 11, 10, 18, 30);

	BookingRepository bookings = mock(BookingRepository.class);
	RoomRepository rooms = mock(RoomRepository.class);
	EquipmentRepository equipment = mock(EquipmentRepository.class);
	UserProfiles profiles = mock(UserProfiles.class);
	EntityManager em = mock(EntityManager.class);
	@SuppressWarnings("unchecked")
	ObjectProvider<TimetableOccupancy> occupancy = mock(ObjectProvider.class);
	NoticeService notices = mock(NoticeService.class);
	Clock clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);

	AppUserEntity bianchi = new AppUserEntity("U02", "L. Bianchi", null, "MATH");
	RoomEntity founders = new RoomEntity("R1", "Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of("Projector"));
	RoomEntity hale = new RoomEntity("R2", "Hale Hall 204", "Hale", 120, RoomType.LECTURE_HALL, Set.of());
	RoomEntity lab = new RoomEntity("R3", "Innovation Lab B", "Innovation", 40, RoomType.LAB, Set.of("Projector", "Whiteboard"));
	EquipmentEntity speaker = new EquipmentEntity("E1", "Portable speaker", "Speaker", 3, null);

	@BeforeEach
	void defaults() {
		when(profiles.ensure(any())).thenReturn(bianchi);
		when(bookings.save(any(BookingEntity.class))).thenAnswer(i -> i.getArgument(0));
		when(occupancy.getIfAvailable(any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(0)).get()); // nothing in use
		when(rooms.findById("R1")).thenReturn(Optional.of(founders));
		when(equipment.findById("E1")).thenReturn(Optional.of(speaker));
		when(rooms.lockById("R1")).thenReturn(Optional.of(founders));
		when(equipment.lockById("E1")).thenReturn(Optional.of(speaker));
	}

	private BookingServiceImpl as(CurrentUser who) {
		BookingServiceImpl s = new BookingServiceImpl(bookings, rooms, equipment, profiles, TestUsers.caller(who),
				clock, occupancy, notices);
		try {
			Field f = BookingServiceImpl.class.getDeclaredField("em");
			f.setAccessible(true);
			f.set(s, em);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
		return s;
	}

	private BookingEntity pendingRoomBooking() {
		BookingEntity b = BookingEntity.forRoom(bianchi, founders, FIVE_PM, SIX_THIRTY, "Guest talk", NOW.minusDays(1));
		when(bookings.findDetailed(b.getId())).thenReturn(Optional.of(b));
		return b;
	}

	private BookingEntity pendingSpeakerBooking() {
		BookingEntity b = BookingEntity.forEquipment(bianchi, speaker, FIVE_PM, SIX_THIRTY, "Guest talk", NOW.minusDays(1));
		when(bookings.findDetailed(b.getId())).thenReturn(Optional.of(b));
		return b;
	}

	// ---------------- request (UC-07) ----------------

	@Test
	@DisplayName("UC-07: an Instructor's room request is saved as PENDING with the current time from the Clock")
	void submitRoomRequest() {
		BookingView v = as(TestUsers.BIANCHI).submitRequest(ResourceType.ROOM, "R1", FIVE_PM, SIX_THIRTY, " Guest talk ");

		ArgumentCaptor<BookingEntity> saved = ArgumentCaptor.forClass(BookingEntity.class);
		verify(bookings).save(saved.capture());
		assertEquals(BookingStatus.PENDING, saved.getValue().getStatus());
		assertEquals(NOW, saved.getValue().getCreatedAt());
		assertEquals("Guest talk", v.purpose());
		assertEquals("Founders 110", v.resourceName());
		assertEquals("L. Bianchi", v.requesterName());
	}

	@Test
	@DisplayName("TC-FR2.3a: room and equipment requests go into the same queue, as the same kind of row")
	void submitEquipmentRequest() {
		BookingView v = as(TestUsers.BIANCHI).submitRequest(ResourceType.EQUIPMENT, "E1", FIVE_PM, SIX_THIRTY, "Talk");
		assertEquals(ResourceType.EQUIPMENT, v.resourceType());
		assertEquals("E1", v.resourceId());
		assertEquals(BookingStatus.PENDING, v.status());
	}

	@Test
	@DisplayName("UC-07 5b: end must be after start (equal is not enough)")
	void windowMustBePositive() {
		for (LocalDateTime end : List.of(FIVE_PM, FIVE_PM.minusMinutes(1))) {
			var e = assertThrows(BusinessRuleException.class,
					() -> as(TestUsers.BIANCHI).submitRequest(ResourceType.ROOM, "R1", FIVE_PM, end, "Talk"));
			assertEquals("End time must be after start time.", e.getMessage());
		}
		verify(bookings, never()).save(any(BookingEntity.class));
	}

	@Test
	@DisplayName("FR2.5: a room in Maintenance or Checked out cannot be requested")
	void unavailableResourceCannotBeRequested() {
		founders.setStatus(ResourceStatus.MAINTENANCE);
		var e = assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.BIANCHI).submitRequest(ResourceType.ROOM, "R1", FIVE_PM, SIX_THIRTY, "Talk"));
		assertTrue(e.getMessage().contains("under maintenance"), e.getMessage());

		speaker.setStatus(ResourceStatus.CHECKED_OUT);
		assertTrue(assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.BIANCHI).submitRequest(ResourceType.EQUIPMENT, "E1", FIVE_PM, SIX_THIRTY, "Talk"))
				.getMessage().contains("checked out"));
	}

	@Test
	void unknownResourceAndMissingPurpose() {
		assertThrows(NotFoundException.class,
				() -> as(TestUsers.BIANCHI).submitRequest(ResourceType.ROOM, "nope", FIVE_PM, SIX_THIRTY, "Talk"));
		assertEquals("Purpose is required.", assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.BIANCHI).submitRequest(ResourceType.ROOM, "R1", FIVE_PM, SIX_THIRTY, " ")).getMessage());
	}

	@Test
	@DisplayName("TR3.2: a Student cannot request or see the queue")
	void studentIsRefused() {
		assertThrows(AccessDeniedException.class,
				() -> as(TestUsers.STUDENT).submitRequest(ResourceType.ROOM, "R1", FIVE_PM, SIX_THIRTY, "Talk"));
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.STUDENT).list(null));
		verify(bookings, never()).save(any(BookingEntity.class));
	}

	// ---------------- the queue ----------------

	@Test
	@DisplayName("TR3.3: a Scheduler sees the whole queue, an Instructor only their own requests")
	void queueVisibility() {
		BookingEntity b = BookingEntity.forRoom(bianchi, founders, FIVE_PM, SIX_THIRTY, "x", NOW);
		when(bookings.findQueue()).thenReturn(List.of(b));
		when(bookings.findQueueForRequester("U02")).thenReturn(List.of(b));

		assertEquals(1, as(TestUsers.MAYA).list(null).size());
		verify(bookings).findQueue();

		assertEquals(1, as(TestUsers.BIANCHI).list(null).size());
		verify(bookings).findQueueForRequester("U02");
		verify(bookings, org.mockito.Mockito.times(1)).findQueue(); // the Instructor did NOT use the full queue
	}

	@Test
	void queueCanBeFilteredByStatus() {
		BookingEntity pending = BookingEntity.forRoom(bianchi, founders, FIVE_PM, SIX_THIRTY, "a", NOW);
		BookingEntity approved = BookingEntity.forRoom(bianchi, hale, FIVE_PM, SIX_THIRTY, "b", NOW);
		approved.markApproved("U01", NOW);
		when(bookings.findQueue()).thenReturn(List.of(pending, approved));

		assertEquals(List.of(pending.getId()),
				as(TestUsers.MAYA).list(BookingStatus.PENDING).stream().map(BookingView::id).toList());
		assertEquals(2, as(TestUsers.MAYA).list(null).size());
	}

	// ---------------- approval (UC-08, NFR3.1) ----------------

	@Test
	@DisplayName("NFR3.1: approval takes the resource lock FIRST, re-reads the booking, THEN checks overlaps")
	void approveLocksBeforeCheckingAndRefreshes() {
		BookingEntity b = pendingRoomBooking();
		when(bookings.countApprovedForRoom("R1", FIVE_PM, SIX_THIRTY)).thenReturn(0L);

		BookingView v = as(TestUsers.MAYA).approve(b.getId());

		InOrder order = inOrder(rooms, em, bookings);
		order.verify(rooms).lockById("R1");
		order.verify(em).refresh(b);
		order.verify(bookings).countApprovedForRoom("R1", FIVE_PM, SIX_THIRTY);
		assertEquals(BookingStatus.APPROVED, v.status());
		assertEquals("U01", b.getDecidedBy());
		assertEquals(NOW, b.getDecidedAt());
		verify(notices).notifyRequester(b.getId(), NoticeEvent.BOOKING_APPROVED);
	}

	@Test
	@DisplayName("TC-FR2.3b / NFR3.1: an overlapping approved booking blocks the approval with an explanation; status unchanged")
	void approveCollision() {
		BookingEntity b = pendingRoomBooking();
		when(bookings.countApprovedForRoom("R1", FIVE_PM, SIX_THIRTY)).thenReturn(1L);

		var e = assertThrows(BookingCollisionException.class, () -> as(TestUsers.MAYA).approve(b.getId()));

		assertTrue(e.getMessage().contains("Founders 110 already has an approved booking that overlaps"), e.getMessage());
		assertTrue(e.getMessage().contains("17:00-18:30"), e.getMessage());
		assertEquals(BookingStatus.PENDING, b.getStatus());
		verify(notices, never()).notifyRequester(any(), any()); // a refused approval tells nobody they were approved
	}

	@Test
	@DisplayName("OI-1: equipment conflicts only when ALL units are taken (3 units: 2 taken is fine, 3 taken is not)")
	void equipmentIsJudgedPerUnit() {
		BookingEntity b = pendingSpeakerBooking();
		when(bookings.countApprovedForEquipment("E1", FIVE_PM, SIX_THIRTY)).thenReturn(2L);
		assertEquals(BookingStatus.APPROVED, as(TestUsers.MAYA).approve(b.getId()).status());

		BookingEntity second = pendingSpeakerBooking();
		when(bookings.countApprovedForEquipment("E1", FIVE_PM, SIX_THIRTY)).thenReturn(3L);
		var e = assertThrows(BookingCollisionException.class, () -> as(TestUsers.MAYA).approve(second.getId()));
		assertTrue(e.getMessage().contains("all 3 units of Portable speaker"), e.getMessage());
	}

	@Test
	@DisplayName("A booking that was already decided cannot be approved again (409)")
	void approveTwice() {
		BookingEntity b = pendingRoomBooking();
		b.markApproved("U01", NOW);
		var e = assertThrows(ConflictingStateException.class, () -> as(TestUsers.MAYA).approve(b.getId()));
		assertEquals("This request is already approved.", e.getMessage());
		verify(bookings, never()).countApprovedForRoom(anyString(), any(), any());
	}

	@Test
	@DisplayName("FR2.5: a room put into Maintenance after the request cannot be approved")
	void approveRefusedWhenResourceWentIntoMaintenance() {
		BookingEntity b = pendingRoomBooking();
		founders.setStatus(ResourceStatus.MAINTENANCE);
		assertTrue(assertThrows(BusinessRuleException.class, () -> as(TestUsers.MAYA).approve(b.getId()))
				.getMessage().contains("under maintenance"));
		assertEquals(BookingStatus.PENDING, b.getStatus());
	}

	@Test
	@DisplayName("TR3.3: an Instructor cannot approve; nothing is read or locked")
	void instructorCannotApprove() {
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.BIANCHI).approve("any"));
		verifyNoMoreInteractions(bookings, rooms, equipment, notices);
	}

	@Test
	void approveUnknownBookingIsNotFound() {
		when(bookings.findDetailed("nope")).thenReturn(Optional.empty());
		assertThrows(NotFoundException.class, () -> as(TestUsers.MAYA).approve("nope"));
	}

	// ---------------- cancel (UC-08 3b) ----------------

	@Test
	@DisplayName("UC-08 3b: cancelling keeps the row, records who and why, and also works on an approved booking")
	void cancel() {
		BookingEntity pending = pendingRoomBooking();
		BookingView v = as(TestUsers.MAYA).cancel(pending.getId(), "Event moved");
		assertEquals(BookingStatus.CANCELLED, v.status());
		assertEquals("Event moved", pending.getCancelNote());
		assertEquals("U01", pending.getDecidedBy());
		verify(rooms).lockById("R1"); // serialised with a concurrent approve
		verify(notices).notifyRequester(pending.getId(), NoticeEvent.BOOKING_DECLINED); // a pending request was turned down

		BookingEntity approved = pendingRoomBooking();
		approved.markApproved("U01", NOW);
		assertEquals(BookingStatus.CANCELLED, as(TestUsers.MAYA).cancel(approved.getId(), null).status());
		verify(notices).notifyRequester(approved.getId(), NoticeEvent.BOOKING_CANCELLED); // an approved one was withdrawn
	}

	@Test
	@DisplayName("A Scheduler who decides their OWN request gets no notice about it")
	void noNoticeWhenDecidingYourOwnRequest() {
		AppUserEntity maya = new AppUserEntity("U01", "Maya Brooks", null, null);
		BookingEntity own = BookingEntity.forRoom(maya, founders, FIVE_PM, SIX_THIRTY, "mine", NOW.minusDays(1));
		when(bookings.findDetailed(own.getId())).thenReturn(Optional.of(own));

		as(TestUsers.MAYA).approve(own.getId());

		assertEquals(BookingStatus.APPROVED, own.getStatus());
		verify(notices, never()).notifyRequester(any(), any());
	}

	@Test
	void cancellingTwiceIsRefused() {
		BookingEntity b = pendingRoomBooking();
		b.markCancelled("U01", NOW, null);
		assertEquals("This request is already cancelled.",
				assertThrows(ConflictingStateException.class, () -> as(TestUsers.MAYA).cancel(b.getId(), null)).getMessage());
	}

	// ---------------- room search (FR2.4, UC-09) ----------------

	private List<String> roomNames(List<RoomView> views) {
		return views.stream().map(RoomView::name).toList();
	}

	@Test
	@DisplayName("TC-FR2.4: rooms with an approved booking in the window are not offered; the rest are")
	void searchExcludesBookedRooms() {
		when(rooms.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE)).thenReturn(List.of(founders, hale, lab));
		when(bookings.busyRoomIds(FIVE_PM, SIX_THIRTY)).thenReturn(List.of("R1"));

		assertEquals(List.of("Hale Hall 204", "Innovation Lab B"),
				roomNames(as(TestUsers.BIANCHI).searchRooms(FIVE_PM, SIX_THIRTY, null, null, null)));
	}

	@Test
	@DisplayName("FR2.5: search asks only for AVAILABLE rooms, so Maintenance and Checked out stay hidden")
	void searchOnlyAsksForAvailableRooms() {
		as(TestUsers.BIANCHI).searchRooms(FIVE_PM, SIX_THIRTY, null, null, null);
		verify(rooms).findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE);
		verify(rooms, never()).findAllByOrderByNameAsc();
	}

	@Test
	@DisplayName("A room the TIMETABLE uses in the window is not offered either (contract with Dev A)")
	void searchExcludesRoomsUsedByTheTimetable() {
		when(rooms.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE)).thenReturn(List.of(founders, hale));
		TimetableOccupancy classes = mock(TimetableOccupancy.class);
		when(classes.roomIdsInUse(FIVE_PM, SIX_THIRTY)).thenReturn(Set.of("R2"));
		when(classes.equipmentUnitsInUse(any(), any())).thenReturn(Map.of());
		doReturn(classes).when(occupancy).getIfAvailable(any()); // doReturn: do not call the earlier Answer while re-stubbing

		assertEquals(List.of("Founders 110"),
				roomNames(as(TestUsers.BIANCHI).searchRooms(FIVE_PM, SIX_THIRTY, null, null, null)));
	}

	@Test
	@DisplayName("TC-FR2.3b: a room with a class in the window cannot be approved")
	void approvalRefusedWhenAClassUsesTheRoom() {
		BookingEntity b = pendingRoomBooking();
		TimetableOccupancy classes = mock(TimetableOccupancy.class);
		when(classes.roomIdsInUse(FIVE_PM, SIX_THIRTY)).thenReturn(Set.of("R1"));
		doReturn(classes).when(occupancy).getIfAvailable(any());

		var e = assertThrows(BookingCollisionException.class, () -> as(TestUsers.MAYA).approve(b.getId()));

		assertTrue(e.getMessage().contains("Founders 110 has a class scheduled"), e.getMessage());
		assertEquals(BookingStatus.PENDING, b.getStatus());
		verify(notices, never()).notifyRequester(any(), any());
	}

	@Test
	@DisplayName("OI-1: classes and approved bookings together can use up every unit")
	void approvalCountsUnitsUsedByClasses() {
		BookingEntity b = pendingSpeakerBooking();
		when(bookings.countApprovedForEquipment("E1", FIVE_PM, SIX_THIRTY)).thenReturn(1L);
		TimetableOccupancy classes = mock(TimetableOccupancy.class);
		when(classes.equipmentUnitsInUse(FIVE_PM, SIX_THIRTY)).thenReturn(Map.of("E1", 2));
		doReturn(classes).when(occupancy).getIfAvailable(any());

		var e = assertThrows(BookingCollisionException.class, () -> as(TestUsers.MAYA).approve(b.getId()));

		assertTrue(e.getMessage().contains("all 3 units of Portable speaker"), e.getMessage());
	}

	@Test
	@DisplayName("OI-1: one unit left after classes and approved bookings can still be approved")
	void approvalAllowedWhileAUnitIsFree() {
		BookingEntity b = pendingSpeakerBooking();
		when(bookings.countApprovedForEquipment("E1", FIVE_PM, SIX_THIRTY)).thenReturn(1L);
		TimetableOccupancy classes = mock(TimetableOccupancy.class);
		when(classes.equipmentUnitsInUse(FIVE_PM, SIX_THIRTY)).thenReturn(Map.of("E1", 1));
		doReturn(classes).when(occupancy).getIfAvailable(any());

		assertEquals(BookingStatus.APPROVED, as(TestUsers.MAYA).approve(b.getId()).status());
	}

	@Test
	void searchFilters() {
		when(rooms.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE)).thenReturn(List.of(founders, hale, lab));
		BookingServiceImpl s = as(TestUsers.BIANCHI);

		assertEquals(List.of("Hale Hall 204"), roomNames(s.searchRooms(FIVE_PM, SIX_THIRTY, 100, null, null)));
		assertEquals(List.of("Innovation Lab B"), roomNames(s.searchRooms(FIVE_PM, SIX_THIRTY, null, RoomType.LAB, null)));
		assertEquals(List.of("Innovation Lab B"), roomNames(s.searchRooms(FIVE_PM, SIX_THIRTY, null, null, "whiteboard")));
		assertEquals(List.of("Founders 110", "Innovation Lab B"),
				roomNames(s.searchRooms(FIVE_PM, SIX_THIRTY, null, null, " Projector ")));
		assertEquals(List.of("Innovation Lab B"), roomNames(s.searchRooms(FIVE_PM, SIX_THIRTY, 40, RoomType.LAB, "Projector")));
		assertTrue(s.searchRooms(FIVE_PM, SIX_THIRTY, 500, null, null).isEmpty());
	}

	@Test
	void searchNeedsAValidWindowAndASignedInRole() {
		assertEquals("End time must be after start time.", assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.BIANCHI).searchRooms(SIX_THIRTY, FIVE_PM, null, null, null)).getMessage());
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.STUDENT).searchRooms(FIVE_PM, SIX_THIRTY, null, null, null));
	}

	// ---------------- equipment search ----------------

	private static BookingRepository.EquipmentUsage usage(String id, long used) {
		return new BookingRepository.EquipmentUsage() {
			@Override
			public String getEquipmentId() {
				return id;
			}

			@Override
			public long getUsed() {
				return used;
			}
		};
	}

	@Test
	@DisplayName("FR2.4: free units = total - approved bookings - units the timetable needs; none left means not offered")
	void equipmentSearchComputesFreeUnits() {
		EquipmentEntity projector = new EquipmentEntity("E2", "Projector", "Projector", 2, null);
		EquipmentEntity mic = new EquipmentEntity("E3", "Mic kit", "Mic kit", 1, null);
		when(equipment.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE)).thenReturn(List.of(mic, projector, speaker));
		when(bookings.approvedEquipmentUsage(FIVE_PM, SIX_THIRTY)).thenReturn(List.of(usage("E1", 1), usage("E3", 1)));
		TimetableOccupancy classes = mock(TimetableOccupancy.class);
		when(classes.roomIdsInUse(any(), any())).thenReturn(Set.of());
		when(classes.equipmentUnitsInUse(FIVE_PM, SIX_THIRTY)).thenReturn(Map.of("E1", 1));
		doReturn(classes).when(occupancy).getIfAvailable(any()); // doReturn: do not call the earlier Answer while re-stubbing

		List<EquipmentView> found = as(TestUsers.BIANCHI).searchEquipment(FIVE_PM, SIX_THIRTY, null);

		// speaker: 3 - 1 (booked) - 1 (class) = 1 free; projector: 2 free; mic kit: 1 - 1 = 0, not offered
		assertEquals(List.of("Projector", "Portable speaker"), found.stream().map(EquipmentView::name).toList());
		assertEquals(List.of(2, 1), found.stream().map(EquipmentView::freeUnits).toList());
		assertEquals(List.of("Portable speaker"),
				as(TestUsers.BIANCHI).searchEquipment(FIVE_PM, SIX_THIRTY, "speaker").stream().map(EquipmentView::name).toList());
	}
}
