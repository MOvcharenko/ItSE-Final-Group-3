package support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A small, independent reading of the timetable in the database, used only to PROVE what TD-1 and TD-2 contain
 * (known conflicts, hours per instructor). It is deliberately simple and does not replace the conflict engine:
 * if both ever disagree, one of them is wrong.
 */
public final class ScheduleModel {

	public record Meeting(String sectionId, String course, String roomId, String roomName, int roomCapacity,
			DayOfWeek day, LocalTime start, LocalTime end, Set<String> instructors) {

		int minutes() {
			return (int) java.time.Duration.between(start, end).toMinutes();
		}
	}

	public record Block(String instructorId, DayOfWeek day, LocalTime start, LocalTime end, String status) {
	}

	private ScheduleModel() {
	}

	public static List<Meeting> meetings(Connection c) throws SQLException {
		Map<String, Set<String>> instructors = new HashMap<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
				"SELECT section_id, instructor_id FROM section_instructor")) {
			while (rs.next()) {
				instructors.computeIfAbsent(rs.getString(1), k -> new TreeSet<>()).add(rs.getString(2));
			}
		}
		List<Meeting> out = new ArrayList<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
				"SELECT m.section_id, s.course_code, r.id, r.name, r.capacity, m.day_of_week, m.start_time, m.end_time "
						+ "FROM section_meeting m JOIN section x ON x.id = m.section_id "
						+ "JOIN subject s ON s.id = x.subject_id JOIN room r ON r.id = x.room_id "
						+ "WHERE m.pattern = 'WEEKLY' ORDER BY m.section_id, m.id")) {
			while (rs.next()) {
				out.add(new Meeting(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
						DayOfWeek.valueOf(rs.getString(6)), rs.getTime(7).toLocalTime(), rs.getTime(8).toLocalTime(),
						instructors.getOrDefault(rs.getString(1), Set.of())));
			}
		}
		return out;
	}

	public static List<Block> blocks(Connection c) throws SQLException {
		List<Block> out = new ArrayList<>();
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
				"SELECT instructor_id, day_of_week, start_time, end_time, status FROM instructor_availability")) {
			while (rs.next()) {
				out.add(new Block(rs.getString(1), DayOfWeek.valueOf(rs.getString(2)), rs.getTime(3).toLocalTime(),
						rs.getTime(4).toLocalTime(), rs.getString(5)));
			}
		}
		return out;
	}

	static boolean overlap(DayOfWeek d1, LocalTime s1, LocalTime e1, DayOfWeek d2, LocalTime s2, LocalTime e2) {
		return d1 == d2 && s1.isBefore(e2) && s2.isBefore(e1); // touching is not overlapping
	}

	/** Every problem the rules of FR4.3 that need only schedule data would report, as stable strings. */
	public static Set<String> conflicts(List<Meeting> meetings, List<Block> blocks, Map<String, Integer> enrolled) {
		Set<String> found = new TreeSet<>();
		for (int i = 0; i < meetings.size(); i++) {
			Meeting a = meetings.get(i);
			for (int j = i + 1; j < meetings.size(); j++) {
				Meeting b = meetings.get(j);
				if (a.sectionId().equals(b.sectionId())
						|| !overlap(a.day(), a.start(), a.end(), b.day(), b.start(), b.end())) {
					continue;
				}
				String pair = a.course() + " + " + b.course();
				if (a.roomId().equals(b.roomId())) {
					found.add("ROOM_DOUBLE_BOOKING " + sorted(a.course(), b.course()) + " in " + a.roomName());
				}
				for (String who : a.instructors()) {
					if (b.instructors().contains(who)) {
						found.add("INSTRUCTOR_OVERLAP " + sorted(a.course(), b.course()) + " " + who);
					}
				}
			}
			for (Block blk : blocks) {
				if ("UNAVAILABLE".equals(blk.status()) && a.instructors().contains(blk.instructorId())
						&& overlap(a.day(), a.start(), a.end(), blk.day(), blk.start(), blk.end())) {
					found.add("INSTRUCTOR_UNAVAILABLE " + a.course() + " " + blk.instructorId());
				}
			}
			Integer n = enrolled.get(a.sectionId());
			if (n != null && n > a.roomCapacity()) {
				found.add("CAPACITY_EXCEEDED " + a.course() + " in " + a.roomName());
			}
		}
		return found;
	}

	private static String sorted(String x, String y) {
		return x.compareTo(y) <= 0 ? x + " + " + y : y + " + " + x;
	}

	/** Minutes per week each instructor teaches, from weekly meetings. */
	public static Map<String, Integer> scheduledMinutes(List<Meeting> meetings) {
		Map<String, Integer> m = new HashMap<>();
		for (Meeting x : meetings) {
			for (String who : x.instructors()) {
				m.merge(who, x.minutes(), Integer::sum);
			}
		}
		return m;
	}

	/** Minutes per week each instructor declared OPEN or PREFER. UNAVAILABLE does not count. */
	public static Map<String, Integer> declaredMinutes(List<Block> blocks) {
		Map<String, Integer> m = new HashMap<>();
		for (Block b : blocks) {
			if (!"UNAVAILABLE".equals(b.status())) {
				m.merge(b.instructorId(), (int) java.time.Duration.between(b.start(), b.end()).toMinutes(), Integer::sum);
			}
		}
		return m;
	}
}
