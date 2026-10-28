package api.dto;

import domain.ResourceStatus;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/rooms/{id}/status and /api/equipment/{id}/status (FR2.5). */
public record ResourceStatusRequest(@NotNull(message = "Status is required") ResourceStatus status) {
}
