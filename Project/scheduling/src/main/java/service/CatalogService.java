package service;

import java.util.List;

import domain.ResourceStatus;
import domain.RoomType;
import service.view.EquipmentView;
import service.view.RoomView;
import service.view.SubjectView;

/**
 * Subjects, Rooms and Equipment catalogs (FR2.1, FR2.2, FR3.1, FR3.2, FR2.5).
 * The caller's identity and role come from CurrentUser inside the implementation, never from arguments
 * (NFR1.3, NFR1.4). Validation failures throw BusinessRuleException; unknown ids throw NotFoundException.
 */
public interface CatalogService {

	List<SubjectView> listSubjects();

	SubjectView addSubject(String courseCode, String title, int creditHours, RoomType defaultRoomType,
			String department, List<String> typicalEquipment);

	List<RoomView> listRooms();

	RoomView addRoom(String name, String building, int capacity, RoomType roomType, List<String> fixedEquipment);

	List<EquipmentView> listEquipment();

	EquipmentView addEquipment(String name, String itemType, int unitCount, String homeLocation);

	/** MAINTENANCE / CHECKED_OUT hide the room from search but keep its booking history (FR2.5). */
	RoomView setRoomStatus(String roomId, ResourceStatus status);

	EquipmentView setEquipmentStatus(String equipmentId, ResourceStatus status);
}
