package api.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import domain.AvailabilityStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AvailabilitySubmissionRequest(
		@NotNull List<@Valid BlockRequest> blocks,
		List<String> equipmentNeeds,
		@Size(max = 2000, message = "Notes must not exceed 2000 characters") String notes
) {

	public record BlockRequest(
			@NotNull DayOfWeek dayOfWeek,
			@NotNull LocalTime startTime,
			@NotNull LocalTime endTime,
			@NotNull AvailabilityStatus status
	) {
	}
}
