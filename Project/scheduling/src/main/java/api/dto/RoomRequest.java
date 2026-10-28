package api.dto;

import java.util.List;

import domain.RoomType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/rooms (FR3.2, UC-10). Messages are shown to the Scheduler as-is (NFR4.1). */
public record RoomRequest(
		@NotBlank(message = "Room name is required") @Size(max = 100, message = "Room name is too long") String name,
		@NotBlank(message = "Building is required") String building,
		@Min(value = 1, message = "Capacity must be at least 1") int capacity,
		@NotNull(message = "Room type is required") RoomType roomType,
		List<String> fixedEquipment) {
}
