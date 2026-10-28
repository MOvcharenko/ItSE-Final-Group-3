package api.dto;

import java.time.LocalDateTime;

import domain.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/bookings (FR2.3, UC-07). One resource per request: a room OR an equipment item
 * (TR2.6). "end must be after start" and "resource is not in Maintenance" are business rules checked
 * by the service, so they come back as 422 with the UC-07 wording.
 */
public record BookingRequest(
		@NotNull(message = "Resource type is required") ResourceType resourceType,
		@NotBlank(message = "Resource is required") String resourceId,
		@NotNull(message = "Start time is required") LocalDateTime start,
		@NotNull(message = "End time is required") LocalDateTime end,
		@NotBlank(message = "Purpose is required") String purpose) {
}
