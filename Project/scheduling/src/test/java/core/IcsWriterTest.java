package core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reads the output back with a small RFC 5545 reader, so these tests check meaning as well as text. */
class IcsWriterTest {

	private static final Instant NOW = Instant.parse("2026-11-03T15:30:00Z");
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);
	private static final LocalTime TEN = LocalTime.of(10, 0);
	private static final LocalTime ELEVEN = LocalTime.of(10, 50);

	private static IcsWriter.Event single(String summary, String location, String description) {
		return new IcsWriter.Event("u1@test", summary, location, description, MONDAY, TEN, ELEVEN, null, false, 0);
	}

	private static String unfold(String ics) {
		return ics.replace("\r\n ", "");
	}

	private static List<Map<String, String>> eventsOf(String ics) {
		List<Map<String, String>> events = new ArrayList<>();
		Map<String, String> current = null;
		for (String line : unfold(ics).split("\r\n")) {
			if (line.equals("BEGIN:VEVENT")) {
				current = new HashMap<>();
			} else if (line.equals("END:VEVENT")) {
				events.add(current);
				current = null;
			} else if (current != null) {
				int colon = line.indexOf(':');
				current.put(line.substring(0, colon), line.substring(colon + 1));
			}
		}
		return events;
	}

	@Test
	@DisplayName("TC-FR6.3: the file has the required calendar properties")
	void envelope() {
		String ics = IcsWriter.write(List.of(), NOW);

		assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:").endsWith("END:VCALENDAR\r\n");
	}

	@Test
	@DisplayName("TC-FR6.3: a weekly meeting becomes one repeating event, ending with the term")
	void weekly() {
		IcsWriter.Event event = new IcsWriter.Event("u1@test", "MATH 210", null, null, MONDAY, TEN, ELEVEN,
				LocalDate.of(2026, 12, 20), false, 0);

		List<Map<String, String>> events = eventsOf(IcsWriter.write(List.of(event), NOW));

		assertThat(events).hasSize(1);
		assertThat(events.get(0))
			.containsEntry("UID", "u1@test")
			.containsEntry("DTSTAMP", "20261103T153000Z")
			.containsEntry("DTSTART", "20260907T100000")
			.containsEntry("DTEND", "20260907T105000")
			.containsEntry("RRULE", "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261220T235959")
			.containsEntry("STATUS", "CONFIRMED");
	}

	@Test
	@DisplayName("TC-FR6.3: an exam or one-time meeting is a single event with no repeat")
	void single() {
		Map<String, String> event = eventsOf(IcsWriter.write(List.of(single("MATH 210 (Exam)", null, null)), NOW)).get(0);

		assertThat(event).containsEntry("SUMMARY", "MATH 210 (Exam)").doesNotContainKey("RRULE");
	}

	@Test
	@DisplayName("TC-FR6.3: each weekday maps to its two-letter code")
	void weekdays() {
		String[] codes = { "MO", "TU", "WE", "TH", "FR", "SA", "SU" };
		for (int i = 0; i < codes.length; i++) {
			IcsWriter.Event event = new IcsWriter.Event("u", "s", null, null, MONDAY.plusDays(i), TEN, ELEVEN, MONDAY.plusDays(30), false, 0);
			assertThat(eventsOf(IcsWriter.write(List.of(event), NOW)).get(0).get("RRULE")).contains("BYDAY=" + codes[i] + ";");
		}
	}

	@Test
	@DisplayName("TC-FR6.3: a tentative event is marked, and missing details leave no empty lines")
	void optionalParts() {
		IcsWriter.Event draft = new IcsWriter.Event("u", "s", null, null, MONDAY, TEN, ELEVEN, null, true, 0);

		String ics = IcsWriter.write(List.of(draft), NOW);

		assertThat(eventsOf(ics).get(0)).containsEntry("STATUS", "TENTATIVE").doesNotContainKeys("LOCATION", "DESCRIPTION");
	}

	@Test
	@DisplayName("TC-FR6.3: a changed event carries its version number")
	void versionOfAChangedEvent() {
		IcsWriter.Event changed = new IcsWriter.Event("u", "s", null, null, MONDAY, TEN, ELEVEN, null, false, 3);

		assertThat(eventsOf(IcsWriter.write(List.of(changed), NOW)).get(0)).containsEntry("SEQUENCE", "3");
	}

	@Test
	@DisplayName("TC-FR6.3: commas, semicolons, backslashes and line breaks are escaped")
	void escaping() {
		String ics = IcsWriter.write(List.of(single("a;b", "Founders 110, Founders", "one\\two\nthree\r\nfour")), NOW);

		assertThat(eventsOf(ics).get(0))
			.containsEntry("SUMMARY", "a\\;b")
			.containsEntry("LOCATION", "Founders 110\\, Founders")
			.containsEntry("DESCRIPTION", "one\\\\two\\nthree\\nfour");
	}

	@Test
	@DisplayName("TC-FR6.3: long lines fold at 75 octets and read back unchanged, without splitting a character")
	void folding() {
		String summary = "é€😀 ".repeat(40);

		String ics = IcsWriter.write(List.of(single(summary, null, null)), NOW);

		for (String line : ics.split("\r\n")) {
			assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
			assertThat(line).doesNotContain("�");
		}
		assertThat(eventsOf(ics).get(0)).containsEntry("SUMMARY", summary);
	}

	@Test
	@DisplayName("TC-FR6.3: every line ends in CRLF, with no bare line feeds")
	void lineEndings() {
		String ics = IcsWriter.write(List.of(single("s", "l", "one\ntwo")), NOW);

		assertThat(ics.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
	}

}
