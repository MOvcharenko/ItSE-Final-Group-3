package service.view;

import domain.ResourceStatus;

/** freeUnits is only filled in by availability search (FR2.4); it is null in catalog listings. */
public record EquipmentView(String id, String name, String itemType, int unitCount, String homeLocation,
		ResourceStatus status, Integer freeUnits) {
}
