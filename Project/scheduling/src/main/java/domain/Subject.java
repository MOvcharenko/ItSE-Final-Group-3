package domain;

import java.util.List;

/**
 * Subject — course definition independent of term placement (TR2.2).
 * Carries the department that scheduler authorization is checked against (TR3.4).
 */
public class Subject {

	private final String subjectId;
	private final String courseCode;
	private final String title;
	private final String department;
	private final int creditHours;
	private final RoomType defaultRoomType;
	private final List<String> typicalEquipment;

	public Subject(String subjectId, String courseCode, String title, String department, int creditHours,
			RoomType defaultRoomType, List<String> typicalEquipment) {
		this.subjectId = subjectId;
		this.courseCode = courseCode;
		this.title = title;
		this.department = department;
		this.creditHours = creditHours;
		this.defaultRoomType = defaultRoomType;
		this.typicalEquipment = typicalEquipment == null ? List.of() : List.copyOf(typicalEquipment);
	}

	public String subjectId() { return subjectId; }
	public String courseCode() { return courseCode; }
	public String title() { return title; }
	public String department() { return department; }
	public int creditHours() { return creditHours; }
	public RoomType defaultRoomType() { return defaultRoomType; }
	public List<String> typicalEquipment() { return typicalEquipment; }

}
