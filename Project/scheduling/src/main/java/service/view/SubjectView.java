package service.view;

import java.util.List;

import domain.RoomType;

/** scheduled=false is shown as "Not yet scheduled" (FR3.1, FR3.4). */
public record SubjectView(String id, String courseCode, String title, int creditHours, RoomType defaultRoomType,
		String department, List<String> typicalEquipment, boolean scheduled) {
}
