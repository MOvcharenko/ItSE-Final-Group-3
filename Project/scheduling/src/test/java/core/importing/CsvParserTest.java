package core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvParserTest {

	@Test
	void plainRows() {
		assertEquals(List.of(List.of("a", "b", "c"), List.of("1", "2", "3")), CsvParser.parse("a,b,c\n1,2,3\n"));
	}

	@Test
	@DisplayName("User's Guide s.5: a value with a comma stays one field when wrapped in double quotes")
	void quotedCommaStaysInOneField() {
		var rows = CsvParser.parse("Projector,Projector,5,\"AV Storage, Hale Hall\"\n");
		assertEquals(List.of("Projector", "Projector", "5", "AV Storage, Hale Hall"), rows.get(0));
	}

	@Test
	@DisplayName("User's Guide s.5: the SAME value without quotes becomes an extra column")
	void unquotedCommaSplitsTheField() {
		assertEquals(5, CsvParser.parse("Projector,Projector,5,AV Storage, Hale Hall").get(0).size());
	}

	@Test
	void doubledQuoteIsALiteralQuote() {
		assertEquals("say \"hi\"", CsvParser.parse("\"say \"\"hi\"\"\",x").get(0).get(0));
	}

	@Test
	void windowsAndOldMacLineEndingsAndBlankLines() {
		var rows = CsvParser.parse("a,b\r\n\r\n1,2\r3,4");
		assertEquals(3, rows.size());
		assertEquals(List.of("3", "4"), rows.get(2));
	}

	@Test
	void quotedValueMayContainANewline() {
		var rows = CsvParser.parse("\"line1\nline2\",x\n");
		assertEquals(1, rows.size());
		assertEquals("line1\nline2", rows.get(0).get(0));
	}

	@Test
	@DisplayName("Excel's UTF-8 byte-order mark does not become part of the first header")
	void byteOrderMarkIsIgnored() {
		assertEquals("room_name", CsvParser.parse("\uFEFFroom_name,building").get(0).get(0));
	}

	@Test
	void spacesBeforeAnOpeningQuoteAreIgnored() {
		assertEquals(List.of("a", "x, y"), CsvParser.parse("a, \"x, y\"").get(0));
	}

	@Test
	void trailingEmptyCellIsKept() {
		assertEquals(List.of("PSY 220", "Cognitive Psychology", "3", "Lecture hall", ""),
				CsvParser.parse("PSY 220,Cognitive Psychology,3,Lecture hall,").get(0));
	}

	@Test
	void quoteThatIsNeverClosedRejectsTheFileAndNamesTheLine() {
		var e = assertThrows(CsvFormatException.class, () -> CsvParser.parse("a,b\nx,\"oops\ny,z\n"));
		assertTrue(e.getMessage().startsWith("Line 2:"), e.getMessage());
	}

	@Test
	void textAfterAClosingQuoteIsRejected() {
		assertThrows(CsvFormatException.class, () -> CsvParser.parse("\"a\"b,c"));
	}

	@Test
	void emptyContentHasNoRows() {
		assertTrue(CsvParser.parse("").isEmpty());
		assertTrue(CsvParser.parse("\n\n  \n").isEmpty());
	}
}
