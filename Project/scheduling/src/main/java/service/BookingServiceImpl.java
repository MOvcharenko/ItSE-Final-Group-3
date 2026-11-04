package service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import domain.BookingStatus;
import domain.NoticeEvent;
import domain.NoticeEvent;
import domain.ResourceStatus;
import domain.ResourceType;
import domain.RoomType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import persistence.AppUserEntity;
import persistence.BookingEntity;
import persistence.BookingRepository;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import service.view.BookingView;
import service.view.EquipmentView;
import service.view.RoomView;

/**
 * The shared room/equipment request queue and availability search (FR2.3-FR2.5, TR4.5, UC-07 to UC-09).
 *
 * Atomic approval (NFR3.1): approve() and cancel() first take a row lock on the ROOM or EQUIPMENT row, then re-read
 * the booking, then check overlaps. Two approvals for the same resource therefore run one after the other, and the
 * second one sees the first one's commit. The database alone cannot guarantee this; the lock is what does.
 * Decisions notify the requester through Dev A's NoticeService, inside the same transaction (UC-08).
 */
@Service
@Transactional
public class BookingServiceImpl implements BookingService {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

	private final BookingRepository bookings;
	private final RoomRepository rooms;
	private final EquipmentRepository equipment;
	private final UserProfiles profiles;
	private final Caller caller;
	private final Clock clock;
	private final ObjectProvider<TimetableOccupancy> occupancy;
	private final NoticeService notices;

	@PersistenceContext
	private EntityManager em;

	public BookingServiceImpl(BookingRepository bookings, RoomRepository rooms, EquipmentRepository equipment,
			UserProfiles profiles, Caller caller, Clock clock, ObjectProvider<TimetableOccupancy> occupancy,
			NoticeService notices) {
		this.bookings = bookings;
		this.rooms = rooms;
		this.equipment = equipment;
		this.profiles = profiles;
		this.caller = caller;
		this.clock = clock;
		this.occupancy = occupancy;
		this.notices = notices;
	}

	// ---------------- request (UC-07) ----------------

	@Override
	public BookingView submitRequest(ResourceType type, String resourceId, LocalDateTime start, LocalDateTime end,
			String purpose) {
		caller.requireSchedulerOrInstructor();
		Check.required(type, "Resource type");
		String id = Check.text(resourceId, "Resource", 100);
		requireValidWindow(start, end);
		String why = Check.text(purpose, "Purpose", 500);

		AppUserEntity requester = profiles.ensure(caller);
		LocalDateTime now = LocalDateTime.now(clock);
		BookingEntity booking;
		if (type == ResourceType.ROOM) {
			RoomEntity room = rooms.findById(id).orElseThrow(() -> new NotFoundException("Room not found."));
			requireBookable(room.getName(), room.getStatus());
			booking = BookingEntity.forRoom(requester, room, start, end, why, now);
		} else {
			EquipmentEntity item = equipment.findById(id)
					.orElseThrow(() -> new NotFoundException("Equipment item not found."));
			requireBookable(item.getName(), item.getStatus());
			booking = BookingEntity.forEquipment(requester, item, start, end, why, now);
		}
		return Views.booking(bookings.save(booking));
	}

	@Override
	@Transactional(readOnly = true)
	public List<BookingView> list(BookingStatus statusOrNull) {
		caller.requireSchedulerOrInstructor();
		List<BookingEntity> queue = caller.isScheduler() ? bookings.findQueue()
				: bookings.findQueueForRequester(caller.id()); // an Instructor sees only their own (TR3.3)
		return queue.stream().filter(b -> statusOrNull == null || b.getStatus() == statusOrNull)
				.map(Views::booking).toList();
	}

	// ---------------- decisions (UC-08) ----------------

	@Override
	public BookingView approve(String bookingId) {
		caller.requireScheduler();
		BookingEntity b = load(bookingId);
		lockResourceAndRefresh(b);

		if (b.getStatus() != BookingStatus.PENDING) {
			throw new ConflictingStateException("This request is already " + b.getStatus().name().toLowerCase(Locale.ROOT) + ".");
		}
		ResourceStatus status = b.getResourceType() == ResourceType.ROOM ? b.getRoom().getStatus() : b.getEquipment().getStatus();
		requireBookable(b.resourceName(), status);

		long taken;
		int capacity;
		if (b.getResourceType() == ResourceType.ROOM) {
			taken = bookings.countApprovedForRoom(b.getRoom().getId(), b.getStartTime(), b.getEndTime());
			capacity = 1;
		} else {
			taken = bookings.countApprovedForEquipment(b.getEquipment().getId(), b.getStartTime(), b.getEndTime());
			capacity = b.getEquipment().getUnitCount(); // OI-1: a conflict only when ALL units are taken
		}
		if (taken >= capacity) {
			String what = b.getResourceType() == ResourceType.ROOM
					? b.resourceName() + " already has an approved booking that overlaps " + window(b) + "."
					: "all " + capacity + " units of " + b.resourceName() + " are already booked for " + window(b) + ".";
			throw new BookingCollisionException("Cannot approve: " + what);
		}

		profiles.ensure(caller);
		b.markApproved(caller.id(), LocalDateTime.now(clock));
		notifyRequester(b, NoticeEvent.BOOKING_APPROVED);
		return Views.booking(b);
	}

	@Override
	public BookingView cancel(String bookingId, String noteOrNull) {
		caller.requireScheduler();
		String note = Check.optional(noteOrNull, "Note", 500);
		BookingEntity b = load(bookingId);
		lockResourceAndRefresh(b); // serialises with a concurrent approve of the same booking

		if (b.getStatus() == BookingStatus.CANCELLED) {
			throw new ConflictingStateException("This request is already cancelled.");
		}
		profiles.ensure(caller);
		boolean wasPending = b.getStatus() == BookingStatus.PENDING;
		b.markCancelled(caller.id(), LocalDateTime.now(clock), note); // frees the slot if it was approved (UC-08 3b)
		// turning down a request is "declined"; withdrawing an approved booking is "cancelled"
		notifyRequester(b, wasPending ? NoticeEvent.BOOKING_DECLINED : NoticeEvent.BOOKING_CANCELLED);
		return Views.booking(b);
	}

	// ---------------- availability search (UC-09, FR2.4) ----------------

	@Override
	@Transactional(readOnly = true)
	public List<RoomView> searchRooms(LocalDateTime start, LocalDateTime end, Integer minCapacity, RoomType type,
			String equipmentType) {
		caller.requireSchedulerOrInstructor();
		requireValidWindow(start, end);
		Set<String> busy = new java.util.HashSet<>(bookings.busyRoomIds(start, end));
		busy.addAll(timetable().roomIdsInUse(start, end));
		String wanted = equipmentType == null ? "" : equipmentType.trim();

		return rooms.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE).stream() // Maintenance / Checked out are hidden (FR2.5)
				.filter(r -> !busy.contains(r.getId()))
				.filter(r -> minCapacity == null || r.getCapacity() >= minCapacity)
				.filter(r -> type == null || r.getRoomType() == type)
				.filter(r -> wanted.isEmpty() || r.getFixedEquipment().stream().anyMatch(i -> i.equalsIgnoreCase(wanted)))
				.map(Views::room).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public List<EquipmentView> searchEquipment(LocalDateTime start, LocalDateTime end, String itemType) {
		caller.requireSchedulerOrInstructor();
		requireValidWindow(start, end);
		Map<String, Long> used = bookings.approvedEquipmentUsage(start, end).stream()
				.collect(Collectors.toMap(BookingRepository.EquipmentUsage::getEquipmentId,
						BookingRepository.EquipmentUsage::getUsed));
		Map<String, Integer> byTimetable = timetable().equipmentUnitsInUse(start, end);
		String wanted = itemType == null ? "" : itemType.trim();

		return equipment.findByStatusOrderByNameAsc(ResourceStatus.AVAILABLE).stream()
				.filter(e -> wanted.isEmpty() || e.getItemType().equalsIgnoreCase(wanted))
				.map(e -> {
					long taken = used.getOrDefault(e.getId(), 0L) + byTimetable.getOrDefault(e.getId(), 0);
					return Views.equipment(e, (int) Math.max(0, e.getUnitCount() - taken));
				})
				.filter(v -> v.freeUnits() > 0)
				.toList();
	}

	// ---------------- helpers ----------------

	/**
	 * The notice is saved in THIS transaction (NoticeService contract), so a rolled-back decision leaves no notice.
	 * A Scheduler who decides their own request does not get a notice about it.
	 */
	private void notifyRequester(BookingEntity b, NoticeEvent event) {
		if (!b.getRequester().getId().equals(caller.id())) {
			notices.notifyRequester(b.getId(), event);
		}
	}

	private TimetableOccupancy timetable() {
		return occupancy.getIfAvailable(TimetableOccupancy::none);
	}

	private BookingEntity load(String bookingId) {
		return bookings.findDetailed(bookingId).orElseThrow(() -> new NotFoundException("Booking request not found."));
	}

	/**
	 * Step 1 of NFR3.1. The booking was read BEFORE the lock, and the lock may have waited for another approval to
	 * commit, so the booking is re-read afterwards. Without the refresh the status check would use stale data.
	 */
	private void lockResourceAndRefresh(BookingEntity b) {
		if (b.getResourceType() == ResourceType.ROOM) {
			rooms.lockById(b.getRoom().getId()).orElseThrow(() -> new NotFoundException("Room not found."));
		} else {
			equipment.lockById(b.getEquipment().getId()).orElseThrow(() -> new NotFoundException("Equipment item not found."));
		}
		em.refresh(b);
	}

	private static void requireValidWindow(LocalDateTime start, LocalDateTime end) {
		Check.required(start, "Start time");
		Check.required(end, "End time");
		if (!end.isAfter(start)) {
			throw new BusinessRuleException("End time must be after start time.");
		}
	}

	private static void requireBookable(String name, ResourceStatus status) {
		switch (status) {
			case MAINTENANCE -> throw new BusinessRuleException(name + " is under maintenance and cannot be booked.");
			case CHECKED_OUT -> throw new BusinessRuleException(name + " is checked out and cannot be booked.");
			default -> { }
		}
	}

	private static String window(BookingEntity b) {
		return DAY.format(b.getStartTime()) + "-" + CLOCK.format(b.getEndTime());
	}
}
