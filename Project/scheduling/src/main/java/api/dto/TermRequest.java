package api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/terms (FR1.1). "End on or after start" is a rule the service checks. */
public record TermRequest(
		@NotBlank(message = "Term name is required") @Size(max = 100, message = "Term name is too long") String name,
		@NotNull(message = "Start date is required") LocalDate startDate,
		@NotNull(message = "End date is required") LocalDate endDate) {
}
