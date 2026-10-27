package domain;

/** Kinds of schedule conflict detected by the conflict rules (FR4). */
public enum ConflictType {
	ROOM_DOUBLE_BOOKING,
	INSTRUCTOR_OVERLAP,
	INSTRUCTOR_UNAVAILABLE,
	EQUIPMENT_DOUBLE_BOOKING,
	CAPACITY_EXCEEDED,
	STUDENT_GROUP_CLASH
}
