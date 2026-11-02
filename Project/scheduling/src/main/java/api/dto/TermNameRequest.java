package api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of PATCH /api/terms/{id} and POST /api/terms/{id}/copy (FR1.1). */
public record TermNameRequest(
		@NotBlank(message = "Term name is required") @Size(max = 100, message = "Term name is too long") String name) {
}
