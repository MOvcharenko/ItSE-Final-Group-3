package domain;

import java.time.LocalDateTime;

/**
 * Booking — resource reservation request in the shared queue (TR2.6).
 * This class only models the state change. The no-double-approval guarantee (NFR3.1)
 * comes from BookingService approving inside a transaction with a row lock.
 */
public class Booking {

	private final String bookingId;
	private final User requester;
	private final ResourceType resourceType;
	private final Room room;
	private final Equipment equipment;
	private final LocalDateTime startTime;
	private final LocalDateTime endTime;
	private final String purpose;
	private BookingStatus status;
	private String cancelNote;

	public Booking(String bookingId, User requester, ResourceType resourceType, Room room,
			Equipment equipment, LocalDateTime startTime, LocalDateTime endTime, String purpose) {
		if (resourceType == ResourceType.ROOM && room == null) {
			throw new IllegalArgumentException("A room booking needs a room.");
		}
		if (resourceType == ResourceType.EQUIPMENT && equipment == null) {
			throw new IllegalArgumentException("An equipment booking needs an equipment item.");
		}
		if (!startTime.isBefore(endTime)) {
			throw new IllegalArgumentException("Start time must be before end time.");
		}
		this.bookingId = bookingId;
		this.requester = requester;
		this.resourceType = resourceType;
		this.room = room;
		this.equipment = equipment;
		this.startTime = startTime;
		this.endTime = endTime;
		this.purpose = purpose;
		this.status = BookingStatus.PENDING;
	}

	/** @return false if the request is no longer pending. */
	public boolean approve() {
		if (status != BookingStatus.PENDING) return false;
		this.status = BookingStatus.APPROVED;
		return true;
	}

	public void cancel(String note) {
		this.status = BookingStatus.CANCELLED;
		this.cancelNote = note;
	}

	public String bookingId() { return bookingId; }
	public User requester() { return requester; }
	public ResourceType resourceType() { return resourceType; }
	public Room room() { return room; }
	public Equipment equipment() { return equipment; }
	public LocalDateTime startTime() { return startTime; }
	public LocalDateTime endTime() { return endTime; }
	public String purpose() { return purpose; }
	public BookingStatus status() { return status; }
	public String cancelNote() { return cancelNote; }

}
