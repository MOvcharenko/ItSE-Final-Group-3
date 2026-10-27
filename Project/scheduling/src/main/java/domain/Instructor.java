package domain;

import java.time.LocalDateTime;

/**
 * Instructor — faculty actor who manages availability and equipment requests (TR3.3).
 */
public class Instructor extends User {

	private final String department;

	public Instructor(String userId, String displayName, String email, String department) {
		super(userId, displayName, email, Role.INSTRUCTOR);
		this.department = department;
	}

	public String department() { return department; }

	public Booking requestBooking(String bookingId, ResourceType type, Room room, Equipment equipment,
			LocalDateTime start, LocalDateTime end, String purpose) {
		return new Booking(bookingId, this, type, room, equipment, start, end, purpose);
	}

}
