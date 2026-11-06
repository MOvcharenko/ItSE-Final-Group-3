package api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/terms/{id} (FR1.1). A field left out keeps its value, so the same call renames a term, moves its
 * dates, or both at once. "End on or after start" and "only a Draft's dates can change" are rules the service checks.
 */
public record TermUpdateRequest(
		@Pattern(regexp = "(?s).*\\S.*", message = "Term name is required")
		@Size(max = 100, message = "Term name is too long") String name,
		LocalDate startDate,
		LocalDate endDate) {
}
