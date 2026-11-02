package persistence;

import domain.EquipmentNeed;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One row of table section_equipment. Lives inside a SectionEntity. */
@Embeddable
public class SectionEquipment {

	@Column(name = "equipment_id", nullable = false, length = 36)
	private String equipmentId;

	@Column(name = "units", nullable = false)
	private int units;

	protected SectionEquipment() {
	}

	SectionEquipment(EquipmentNeed need) {
		this.equipmentId = need.equipmentId();
		this.units = need.units();
	}

	EquipmentNeed toNeed() {
		return new EquipmentNeed(equipmentId, units);
	}
}
