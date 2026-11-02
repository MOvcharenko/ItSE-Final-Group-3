package persistence;

import java.util.UUID;

import domain.ResourceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Portable equipment catalog entry (TR2.5, table equipment): a named item with a number of identical units. */
@Entity
@Table(name = "equipment")
public class EquipmentEntity extends AssignedIdEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "item_type", nullable = false, length = 100)
	private String itemType;

	@Column(name = "unit_count", nullable = false)
	private int unitCount;

	@Column(name = "home_location", length = 200)
	private String homeLocation;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ResourceStatus status = ResourceStatus.AVAILABLE;

	protected EquipmentEntity() {
	}

	public EquipmentEntity(String id, String name, String itemType, int unitCount, String homeLocation) {
		super(id);
		this.name = name;
		this.itemType = itemType;
		this.unitCount = unitCount;
		this.homeLocation = homeLocation;
	}

	public static EquipmentEntity create(String name, String itemType, int unitCount, String homeLocation) {
		return new EquipmentEntity(UUID.randomUUID().toString(), name, itemType, unitCount, homeLocation);
	}

	public String getName() {
		return name;
	}

	public String getItemType() {
		return itemType;
	}

	public int getUnitCount() {
		return unitCount;
	}

	public String getHomeLocation() {
		return homeLocation;
	}

	public ResourceStatus getStatus() {
		return status;
	}

	public void setStatus(ResourceStatus status) {
		this.status = status;
	}
}
