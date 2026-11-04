package service;

import java.util.List;

import persistence.BookingEntity;
import persistence.EquipmentEntity;
import persistence.RoomEntity;
import persistence.SubjectEntity;
import service.view.BookingView;
import service.view.EquipmentView;
import service.view.RoomView;
import service.view.SubjectView;

/** Entity to view mapping. Entities never leave the service layer. Call it inside the transaction (lazy fields). */
final class Views {

	private Views() {
	}

	private static List<String> sorted(java.util.Collection<String> items) {
		return items.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
	}

	static RoomView room(RoomEntity r) {
		return new RoomView(r.getId(), r.getName(), r.getBuilding(), r.getCapacity(), r.getRoomType(),
				sorted(r.getFixedEquipment()), r.getStatus());
	}

	/** freeUnits only for availability search; null in catalog listings. */
	static EquipmentView equipment(EquipmentEntity e, Integer freeUnits) {
		return new EquipmentView(e.getId(), e.getName(), e.getItemType(), e.getUnitCount(), e.getHomeLocation(),
				e.getStatus(), freeUnits);
	}

	static SubjectView subject(SubjectEntity s, boolean scheduled) {
		return new SubjectView(s.getId(), s.getCourseCode(), s.getTitle(), s.getCreditHours(), s.getDefaultRoomType(),
				s.getDepartment(), sorted(s.getTypicalEquipment()), scheduled);
	}

	static BookingView booking(BookingEntity b) {
		return new BookingView(b.getId(), b.getRequester().getId(), b.getRequester().getDisplayName(),
				b.getResourceType(), b.resourceId(), b.resourceName(), b.getStartTime(), b.getEndTime(),
				b.getPurpose(), b.getStatus());
	}
}
