package core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes an iCalendar file (RFC 5545, FR6.3). Times are "floating" local times with no time zone, which a calendar
 * app shows at the same clock time wherever it runs; a campus timetable means that, and the data has no zone.
 */
public final class IcsWriter {

	private static final String CRLF = "\r\n";
	private static final int MAX_LINE_OCTETS = 75;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
			.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss", Locale.ROOT);

	private IcsWriter() {
	}

	/**
	 * One event. weeklyUntil is the last possible date of a weekly repeat, or null for a single event. location and
	 * description may be null. A tentative event belongs to a timetable that is not published yet. A higher sequence
	 * marks a newer version of the same uid.
	 */
	public record Event(String uid, String summary, String location, String description, LocalDate date, LocalTime start,
			LocalTime end, LocalDate weeklyUntil, boolean tentative, int sequence) {
	}

	public static String write(List<Event> events, Instant now) {
		StringBuilder ics = new StringBuilder();
		line(ics, "BEGIN:VCALENDAR");
		line(ics, "VERSION:2.0");
		line(ics, "PRODID:-//SDT213 Team 3//Academic Scheduling//EN");
		line(ics, "CALSCALE:GREGORIAN");
		line(ics, "METHOD:PUBLISH");
		for (Event event : events) {
			writeEvent(ics, event, now);
		}
		line(ics, "END:VCALENDAR");
		return ics.toString();
	}

	private static void writeEvent(StringBuilder ics, Event event, Instant now) {
		line(ics, "BEGIN:VEVENT");
		line(ics, "UID:" + escape(event.uid()));
		line(ics, "DTSTAMP:" + STAMP.format(now));
		line(ics, "SEQUENCE:" + event.sequence());
		line(ics, "DTSTART:" + dateTime(event.date(), event.start()));
		line(ics, "DTEND:" + dateTime(event.date(), event.end()));
		if (event.weeklyUntil() != null) {
			// DTSTART is floating, so UNTIL must be too (RFC 5545 section 3.3.10).
			line(ics, "RRULE:FREQ=WEEKLY;BYDAY=" + event.date().getDayOfWeek().name().substring(0, 2) + ";UNTIL="
					+ dateTime(event.weeklyUntil(), LocalTime.of(23, 59, 59)));
		}
		line(ics, "SUMMARY:" + escape(event.summary()));
		if (event.location() != null) {
			line(ics, "LOCATION:" + escape(event.location()));
		}
		if (event.description() != null) {
			line(ics, "DESCRIPTION:" + escape(event.description()));
		}
		line(ics, "STATUS:" + (event.tentative() ? "TENTATIVE" : "CONFIRMED"));
		line(ics, "END:VEVENT");
	}

	private static String dateTime(LocalDate date, LocalTime time) {
		return date.format(DATE) + "T" + time.format(TIME);
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n")
				.replace("\n", "\\n").replace("\r", "\\n");
	}

	/** Lines over 75 octets continue on the next line after one space, never splitting a multi-byte character. */
	private static void line(StringBuilder ics, String content) {
		int octets = 0;
		for (int i = 0; i < content.length();) {
			int codePoint = content.codePointAt(i);
			int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
			if (octets + size > MAX_LINE_OCTETS) {
				ics.append(CRLF).append(' ');
				octets = 1;
			}
			ics.appendCodePoint(codePoint);
			octets += size;
			i += Character.charCount(codePoint);
		}
		ics.append(CRLF);
	}

}
