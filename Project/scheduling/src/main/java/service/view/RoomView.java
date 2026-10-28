package service.view;

import java.util.List;

import domain.ResourceStatus;
import domain.RoomType;

public record RoomView(String id, String name, String building, int capacity, RoomType roomType,
		List<String> fixedEquipment, ResourceStatus status) {
}
