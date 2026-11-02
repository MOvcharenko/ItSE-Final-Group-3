package core.importing;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import domain.RoomType;

/**
 * The typed content of one valid row per import type. Syntax only: whether a room, term or instructor exists,
 * or a course code is already taken, is decided by the service against the database.
 */
public final class ImportRows {

	private ImportRows() {
	}

	public record RoomRow(String name, String building, int capacity, RoomType roomType, List<String> fixedEquipment) {
	}

	public record EquipmentRow(String name, String itemType, int units, String homeLocation) {
	}

	public record SubjectRow(String courseCode, String title, int creditHours, RoomType defaultRoomType,
			List<String> typicalEquipment) {
	}

	public record ScheduleRow(String courseCode, String title, List<DayOfWeek> days, LocalTime start, LocalTime end,
			String roomName, List<String> instructorNames, String termName) {
	}
}
