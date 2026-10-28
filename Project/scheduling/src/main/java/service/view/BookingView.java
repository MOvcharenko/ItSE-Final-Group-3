package service.view;

import java.time.LocalDateTime;

import domain.BookingStatus;
import domain.ResourceType;

/** One row of the shared request queue: Room and Equipment look the same (TR4.5). */
public record BookingView(String id, String requesterId, String requesterName, ResourceType resourceType,
		String resourceId, String resourceName, LocalDateTime start, LocalDateTime end, String purpose,
		BookingStatus status) {
}
