package domain;

import java.util.List;

/**
 * Room — catalog entry for a physical campus space (TR2.4).
 * {@code name} is unique (e.g. "Founders 110") and is what import files reference.
 */
public class Room {

	private final String roomId;
	private final String name;
	private final String building;
	private final int capacity;
	private final RoomType roomType;
	private final List<String> fixedEquipment;
	private ResourceStatus status;

	public Room(String roomId, String name, String building, int capacity, RoomType roomType,
			List<String> fixedEquipment, ResourceStatus status) {
		if (capacity < 1) {
			throw new IllegalArgumentException("Capacity must be 1 or more.");
		}
		this.roomId = roomId;
		this.name = name;
		this.building = building;
		this.capacity = capacity;
		this.roomType = roomType;
		this.fixedEquipment = fixedEquipment == null ? List.of() : List.copyOf(fixedEquipment);
		this.status = status;
	}

	public boolean isBookable() { return status == ResourceStatus.AVAILABLE; }
	public boolean fitsCapacity(int n) { return capacity >= n; }
	public void setStatus(ResourceStatus status) { this.status = status; }

	public String roomId() { return roomId; }
	public String name() { return name; }
	public String building() { return building; }
	public int capacity() { return capacity; }
	public RoomType roomType() { return roomType; }
	public List<String> fixedEquipment() { return fixedEquipment; }
	public ResourceStatus status() { return status; }
	public String label() { return name; }

}
