package core.conflict;

import java.time.LocalDateTime;
import java.util.Objects;

import domain.BookingStatus;
import domain.ResourceType;

/**
 * Immutable snapshot of an approved booking for conflict evaluation.
 * Framework-free: plain Java record.
 */
public record ScheduledBooking(
		String bookingId,
		ResourceType resourceType,
		String resourceId,
		String resourceName,
		LocalDateTime startTime,
		LocalDateTime endTime,
		String purpose,
		BookingStatus status
) {
	public ScheduledBooking {
		Objects.requireNonNull(bookingId, "bookingId must not be null");
		Objects.requireNonNull(resourceType, "resourceType must not be null");
		Objects.requireNonNull(resourceId, "resourceId must not be null");
		Objects.requireNonNull(startTime, "startTime must not be null");
		Objects.requireNonNull(endTime, "endTime must not be null");
		resourceName = resourceName != null ? resourceName : resourceId;
		purpose = purpose != null ? purpose : "";
		status = status != null ? status : BookingStatus.PENDING;
	}

	public boolean isApproved() {
		return status == BookingStatus.APPROVED;
	}
}
