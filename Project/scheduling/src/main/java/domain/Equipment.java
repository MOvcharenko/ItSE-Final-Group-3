package domain;

/**
 * Equipment — catalog entry for portable AV/hardware items (TR2.5).
 * Conflicts are judged per unit: only when overlapping reservations exceed {@code unitCount} (OI-1).
 */
public class Equipment {

	private final String equipmentId;
	private final String itemType;
	private final int unitCount;
	private final String homeLocation;
	private ResourceStatus status;

	public Equipment(String equipmentId, String itemType, int unitCount, String homeLocation, ResourceStatus status) {
		if (unitCount < 1) {
			throw new IllegalArgumentException("Unit count must be 1 or more.");
		}
		this.equipmentId = equipmentId;
		this.itemType = itemType;
		this.unitCount = unitCount;
		this.homeLocation = homeLocation;
		this.status = status;
	}

	public boolean isAvailable() { return status == ResourceStatus.AVAILABLE; }
	public void setStatus(ResourceStatus status) { this.status = status; }

	public String equipmentId() { return equipmentId; }
	public String itemType() { return itemType; }
	public int unitCount() { return unitCount; }
	public String homeLocation() { return homeLocation; }
	public ResourceStatus status() { return status; }

}
