package api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/equipment (FR2.2, FR3.2). Home location is optional. */
public record EquipmentRequest(
		@NotBlank(message = "Item name is required") @Size(max = 100, message = "Item name is too long") String name,
		@NotBlank(message = "Item type is required") String itemType,
		@Min(value = 1, message = "Unit count must be at least 1") int unitCount,
		String homeLocation) {
}
