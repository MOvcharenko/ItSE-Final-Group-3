package service;

import java.time.LocalDateTime;
import java.util.List;

import domain.BookingStatus;
import domain.ResourceType;
import domain.RoomType;
import service.view.BookingView;
import service.view.EquipmentView;
import service.view.RoomView;

/** One shared request queue for rooms and equipment (FR2.3, TR4.5) plus availability search (FR2.4). */
public interface BookingService {

	/** UC-07: creates a PENDING booking for exactly one resource. */
	BookingView submitRequest(ResourceType type, String resourceId, LocalDateTime start, LocalDateTime end,
			String purpose);

	/** Scheduler: every request. Instructor: only their own. statusOrNull = no filter. */
	List<BookingView> list(BookingStatus statusOrNull);

	/** UC-08: atomic. Throws BookingCollisionException if a concurrent approval got there first (NFR3.1). */
	BookingView approve(String bookingId);

	BookingView cancel(String bookingId, String noteOrNull);

	/** UC-09: rooms free for the WHOLE window; touching windows do not block. Null filters are ignored. */
	List<RoomView> searchRooms(LocalDateTime start, LocalDateTime end, Integer minCapacity, RoomType type,
			String equipment);

	/** UC-09: items with at least one free unit for the whole window; freeUnits is filled in. */
	List<EquipmentView> searchEquipment(LocalDateTime start, LocalDateTime end, String itemType);
}
