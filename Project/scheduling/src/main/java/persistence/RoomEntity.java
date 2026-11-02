package persistence;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import domain.ResourceStatus;
import domain.RoomType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/** Room catalog entry (TR2.4, table room). Never deleted once booked: set a status instead (FR2.5). */
@Entity
@Table(name = "room")
public class RoomEntity extends AssignedIdEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "building", nullable = false, length = 100)
	private String building;

	@Column(name = "capacity", nullable = false)
	private int capacity;

	@Enumerated(EnumType.STRING)
	@Column(name = "room_type", nullable = false, length = 20)
	private RoomType roomType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ResourceStatus status = ResourceStatus.AVAILABLE;

	/** Small lists, always needed with the room: loaded together with it. */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "room_fixed_equipment", joinColumns = @JoinColumn(name = "room_id"))
	@Column(name = "item_type", nullable = false, length = 100)
	private Set<String> fixedEquipment = new LinkedHashSet<>();

	protected RoomEntity() {
	}

	public RoomEntity(String id, String name, String building, int capacity, RoomType roomType,
			Set<String> fixedEquipment) {
		super(id);
		this.name = name;
		this.building = building;
		this.capacity = capacity;
		this.roomType = roomType;
		this.fixedEquipment = new LinkedHashSet<>(fixedEquipment);
	}

	public static RoomEntity create(String name, String building, int capacity, RoomType roomType,
			Set<String> fixedEquipment) {
		return new RoomEntity(UUID.randomUUID().toString(), name, building, capacity, roomType, fixedEquipment);
	}

	public String getName() {
		return name;
	}

	public String getBuilding() {
		return building;
	}

	public int getCapacity() {
		return capacity;
	}

	public RoomType getRoomType() {
		return roomType;
	}

	public ResourceStatus getStatus() {
		return status;
	}

	public void setStatus(ResourceStatus status) {
		this.status = status;
	}

	public Set<String> getFixedEquipment() {
		return fixedEquipment;
	}
}
