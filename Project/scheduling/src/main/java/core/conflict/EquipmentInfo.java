package core.conflict;

import java.util.Objects;

/**
 * Immutable equipment metadata for capacity checking (OI-1).
 * Framework-free: plain Java record.
 */
public record EquipmentInfo(
		String id,
		String name,
		String itemType,
		int unitCount
) {
	public EquipmentInfo {
		Objects.requireNonNull(id, "equipment id must not be null");
		name = name != null ? name : id;
		itemType = itemType != null ? itemType : "";
		if (unitCount <= 0) {
			throw new IllegalArgumentException("Unit count must be greater than zero.");
		}
	}
}
