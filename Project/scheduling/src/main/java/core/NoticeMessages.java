package core;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import domain.MeetingPattern;
import domain.MeetingSlot;

/** The plain-language wording of every notice (NFR4.1, UC-18). */
public final class NoticeMessages {

	private static final int MAX_LENGTH = 1000;
	private static final String SEE_CALENDAR = " ... See your calendar for the full details.";

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

	private static final Comparator<MeetingSlot> WEEKLY_THEN_DATED = Comparator
			.comparing((MeetingSlot slot) -> slot.pattern() != MeetingPattern.WEEKLY)
			.thenComparing(MeetingSlot::day, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MeetingSlot::date, Comparator.nullsLast(Comparator.naturalOrder()))
			.thenComparing(MeetingSlot::start);

	private NoticeMessages() {
	}

	public static String sectionLabel(String courseCode, String sectionNumber) {
		return courseCode + " section " + sectionNumber;
	}

	public static String sectionChanged(String section, String roomName, List<MeetingSlot> meetings,
			Collection<String> instructorNames) {
		String times = meetings.stream().sorted(WEEKLY_THEN_DATED).map(NoticeMessages::describe)
				.collect(Collectors.joining(", "));
		String place = roomName == null ? "has no room assigned yet" : "is in " + roomName;
		return section + " has changed. It " + place + " and meets " + times + ". Instructors: " + names(instructorNames)
				+ ".";
	}

	/** Shortens a message to fit the notice table, so a long one can never make the change behind it fail. */
	public static String fit(String message) {
		if (message.length() <= MAX_LENGTH) {
			return message;
		}
		int end = MAX_LENGTH - SEE_CALENDAR.length();
		if (Character.isHighSurrogate(message.charAt(end - 1))) {
			end--;
		}
		return message.substring(0, end) + SEE_CALENDAR;
	}

	public static String sectionCancelled(String section) {
		return section + " has been cancelled.";
	}

	public static String instructorsChanged(String section, Collection<String> instructorNames) {
		return "The instructors for " + section + " have changed. Now teaching: " + names(instructorNames) + ".";
	}

	private static String names(Collection<String> names) {
		return names.stream().sorted().collect(Collectors.joining(", "));
	}

	public static String noLongerTeaching(String section) {
		return "You are no longer teaching " + section + ".";
	}

	public static String bookingApproved(String resource, LocalDateTime start, LocalDateTime end) {
		return "Your request for " + resource + " on " + window(start, end) + " was approved.";
	}

	public static String bookingDeclined(String resource, LocalDateTime start, LocalDateTime end, String note) {
		return withNote("Your request for " + resource + " on " + window(start, end) + " was declined.", note);
	}

	public static String bookingCancelled(String resource, LocalDateTime start, LocalDateTime end, String note) {
		return withNote("Your booking of " + resource + " on " + window(start, end) + " was cancelled.", note);
	}

	private static String withNote(String message, String note) {
		return note == null || note.isBlank() ? message : message + " Note: " + note;
	}

	private static String describe(MeetingSlot slot) {
		String times = times(slot.start(), slot.end());
		return switch (slot.pattern()) {
			case WEEKLY -> slot.day().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + times;
			case ONE_TIME -> "once on " + slot.date().format(DATE) + " " + times;
			case EXAM -> "for the exam on " + slot.date().format(DATE) + " " + times;
		};
	}

	private static String times(LocalTime start, LocalTime end) {
		return start.format(TIME) + "–" + end.format(TIME);
	}

	private static String window(LocalDateTime start, LocalDateTime end) {
		String date = start.format(DATE) + ", ";
		if (start.toLocalDate().equals(end.toLocalDate())) {
			return date + times(start.toLocalTime(), end.toLocalTime());
		}
		return date + start.format(TIME) + " to " + end.format(DATE) + ", " + end.format(TIME);
	}

}
