package core;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import domain.MeetingPattern;
import domain.MeetingSlot;

/**
 * Pure domain representation of a meeting interval (FR1.3).
 * Framework-free: imports nothing from Spring or JPA.
 * Touching intervals (e.g. 10:00–11:15 and 11:15–12:00) do NOT overlap.
 */
public final class MeetingTime {

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

	private final MeetingPattern pattern;
	private final DayOfWeek dayOfWeek;
	private final LocalDate date;
	private final LocalTime start;
	private final LocalTime end;

	public MeetingTime(MeetingPattern pattern, DayOfWeek dayOfWeek, LocalDate date, LocalTime start, LocalTime end) {
		this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
		this.start = Objects.requireNonNull(start, "start time must not be null");
		this.end = Objects.requireNonNull(end, "end time must not be null");

		if (!start.isBefore(end)) {
			throw new IllegalArgumentException("Start time (" + start + ") must be before end time (" + end + ").");
		}

		if (pattern == MeetingPattern.WEEKLY) {
			if (dayOfWeek == null) {
				throw new IllegalArgumentException("Weekly meeting requires a dayOfWeek.");
			}
			this.dayOfWeek = dayOfWeek;
			this.date = null;
		} else {
			if (date == null) {
				throw new IllegalArgumentException(pattern + " meeting requires a date.");
			}
			this.date = date;
			this.dayOfWeek = dayOfWeek != null ? dayOfWeek : date.getDayOfWeek();
		}
	}

	public static MeetingTime weekly(DayOfWeek day, LocalTime start, LocalTime end) {
		return new MeetingTime(MeetingPattern.WEEKLY, day, null, start, end);
	}

	public static MeetingTime weekly(DayOfWeek day, String start, String end) {
		return weekly(day, LocalTime.parse(start), LocalTime.parse(end));
	}

	public static MeetingTime oneTime(LocalDate date, LocalTime start, LocalTime end) {
		return new MeetingTime(MeetingPattern.ONE_TIME, null, date, start, end);
	}

	public static MeetingTime oneTime(LocalDate date, String start, String end) {
		return oneTime(date, LocalTime.parse(start), LocalTime.parse(end));
	}

	public static MeetingTime exam(LocalDate date, LocalTime start, LocalTime end) {
		return new MeetingTime(MeetingPattern.EXAM, null, date, start, end);
	}

	public static MeetingTime exam(LocalDate date, String start, String end) {
		return exam(date, LocalTime.parse(start), LocalTime.parse(end));
	}

	public static MeetingTime fromSlot(MeetingSlot slot) {
		return new MeetingTime(slot.pattern(), slot.day(), slot.date(), slot.start(), slot.end());
	}

	public MeetingPattern pattern() {
		return pattern;
	}

	public DayOfWeek dayOfWeek() {
		return dayOfWeek;
	}

	public LocalDate date() {
		return date;
	}

	public LocalTime start() {
		return start;
	}

	public LocalTime end() {
		return end;
	}

	/**
	 * Checks whether this meeting overlaps with another.
	 * Touching windows (start == other.end or end == other.start) do NOT overlap.
	 */
	public boolean overlaps(MeetingTime other) {
		if (other == null) {
			return false;
		}
		if (!daysMatch(other)) {
			return false;
		}
		return timeOverlaps(other.start, other.end);
	}

	/**
	 * Checks whether this meeting overlaps with a weekly window on a day of week.
	 */
	public boolean overlaps(DayOfWeek day, LocalTime otherStart, LocalTime otherEnd) {
		if (day == null || otherStart == null || otherEnd == null) {
			return false;
		}
		DayOfWeek effectiveDay = (pattern == MeetingPattern.WEEKLY) ? this.dayOfWeek : (this.date != null ? this.date.getDayOfWeek() : null);
		if (effectiveDay != day) {
			return false;
		}
		return timeOverlaps(otherStart, otherEnd);
	}

	private boolean daysMatch(MeetingTime other) {
		if (this.pattern == MeetingPattern.WEEKLY && other.pattern == MeetingPattern.WEEKLY) {
			return this.dayOfWeek == other.dayOfWeek;
		}
		if (this.date != null && other.date != null) {
			return this.date.equals(other.date);
		}
		// One is weekly, one is dated: match if the dated meeting's date falls on the weekly day of week
		if (this.pattern == MeetingPattern.WEEKLY && other.date != null) {
			return this.dayOfWeek == other.date.getDayOfWeek();
		}
		if (this.date != null && other.pattern == MeetingPattern.WEEKLY) {
			return this.date.getDayOfWeek() == other.dayOfWeek;
		}
		return false;
	}

	private boolean timeOverlaps(LocalTime otherStart, LocalTime otherEnd) {
		// Touching windows do not overlap: [start, end) and [otherStart, otherEnd)
		return this.start.isBefore(otherEnd) && otherStart.isBefore(this.end);
	}

	/**
	 * Calculates the duration of overlap between this and another meeting.
	 * Returns Duration.ZERO if they do not overlap.
	 */
	public Duration overlapDuration(MeetingTime other) {
		if (!overlaps(other)) {
			return Duration.ZERO;
		}
		return overlapDuration(other.start, other.end);
	}

	public Duration overlapDuration(LocalTime otherStart, LocalTime otherEnd) {
		LocalTime overlapStart = this.start.isAfter(otherStart) ? this.start : otherStart;
		LocalTime overlapEnd = this.end.isBefore(otherEnd) ? this.end : otherEnd;
		if (overlapStart.isBefore(overlapEnd)) {
			return Duration.between(overlapStart, overlapEnd);
		}
		return Duration.ZERO;
	}

	public String formatWindow() {
		String dayStr = (pattern == MeetingPattern.WEEKLY) ? String.valueOf(dayOfWeek) : String.valueOf(date);
		return dayStr + " " + start.format(TIME_FORMAT) + "–" + end.format(TIME_FORMAT);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MeetingTime that)) return false;
		return pattern == that.pattern &&
				dayOfWeek == that.dayOfWeek &&
				Objects.equals(date, that.date) &&
				Objects.equals(start, that.start) &&
				Objects.equals(end, that.end);
	}

	@Override
	public int hashCode() {
		return Objects.hash(pattern, dayOfWeek, date, start, end);
	}

	@Override
	public String toString() {
		return "MeetingTime[" + pattern + ", " + formatWindow() + "]";
	}
}
