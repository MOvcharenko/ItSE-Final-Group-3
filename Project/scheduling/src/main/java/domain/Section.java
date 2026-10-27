package domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Section — scheduled instance of a Subject (TR2.3).
 * TODO(Dev B): still holds a single meeting. Replace day/start/end with a list of
 * MeetingTime (weekly, one-time, exam) from the starter core package, so MWF rows
 * and exams fit (plan section 3, fix 1).
 */
public class Section {

	private final String sectionId;
	private final String courseCode;
	private final String termId;
	private final MeetingPattern pattern;
	private DayOfWeek day;
	private LocalTime start;
	private LocalTime end;
	private Room room;
	private final List<Instructor> instructors = new ArrayList<>();
	private final List<Equipment> requestedEquipment = new ArrayList<>();
	private int enrolledCount;

	public Section(String sectionId, String courseCode, String termId, MeetingPattern pattern, int enrolledCount) {
		this.sectionId = sectionId;
		this.courseCode = courseCode;
		this.termId = termId;
		this.pattern = pattern;
		this.enrolledCount = enrolledCount;
	}

	public void place(DayOfWeek day, LocalTime start, LocalTime end, Room room, List<Instructor> instructors) {
		if (!start.isBefore(end)) {
			throw new IllegalArgumentException("Start time must be before end time.");
		}
		if (instructors == null || instructors.isEmpty()) {
			throw new IllegalArgumentException("A section needs at least one instructor (TR2.3).");
		}
		this.day = day;
		this.start = start;
		this.end = end;
		this.room = room;
		this.instructors.clear();
		this.instructors.addAll(instructors);
	}

	public void assignRoom(Room r) { this.room = r; }

	public void addInstructor(Instructor i) {
		if (!instructors.contains(i)) instructors.add(i);
	}

	public void addEquipment(Equipment e) {
		if (!requestedEquipment.contains(e)) requestedEquipment.add(e);
	}

	public boolean isPlaced() { return day != null && room != null; }

	/** Same term, same day, and the time ranges overlap; back-to-back is not an overlap. */
	public boolean overlaps(Section o) {
		return isPlaced() && o.isPlaced() && termId.equals(o.termId) && day == o.day
				&& start.isBefore(o.end) && o.start.isBefore(end);
	}

	public String sectionId() { return sectionId; }
	public String courseCode() { return courseCode; }
	public String termId() { return termId; }
	public MeetingPattern pattern() { return pattern; }
	public DayOfWeek day() { return day; }
	public LocalTime start() { return start; }
	public LocalTime end() { return end; }
	public Room room() { return room; }
	public List<Instructor> instructors() { return List.copyOf(instructors); }
	public List<Equipment> requestedEquipment() { return List.copyOf(requestedEquipment); }
	public int enrolledCount() { return enrolledCount; }
	public void setEnrolledCount(int n) { this.enrolledCount = n; }

	public String describe() {
		return courseCode + " (" + day + " " + start + "-" + end + ", " + (room == null ? "no room" : room.label()) + ")";
	}

}
