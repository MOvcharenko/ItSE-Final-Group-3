package api.dto;

import jakarta.validation.constraints.Size;

/** Optional body of POST /api/bookings/{id}/cancel (UC-08 3b). */
public record CancelRequest(@Size(max = 500, message = "Note is too long (500 characters at most)") String note) {
}
