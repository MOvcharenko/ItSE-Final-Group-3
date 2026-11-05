package api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload to dismiss a conflict with a mandatory reason (UC-05 4b, NFR3.3).
 */
public record DismissConflictRequest(
		@NotBlank(message = "A reason is required to dismiss a conflict.")
		@Size(max = 500, message = "Reason cannot exceed 500 characters.")
		String reason
) {
}
