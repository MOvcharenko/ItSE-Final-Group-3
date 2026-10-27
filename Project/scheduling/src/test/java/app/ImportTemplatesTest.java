package app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The import templates (FR3.3) ship on the classpath and keep the headers the importer expects.
 */
class ImportTemplatesTest {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
		"room-import-template.csv|room_name,building,capacity,room_type,equipment",
		"equipment-import-template.csv|item_name,type,units,home_location",
		"subject-list-template.csv|subject_code,title,credit_hours,default_room_type,equipment_needed",
		"schedule-import-template.csv|subject_code,title,days,start_time,end_time,room,instructor,term",
	})
	void templateHasExpectedHeader(String file, String header) throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/import-templates/" + file)) {
			assertThat(in).as(file + " on classpath").isNotNull();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			assertThat(reader.readLine().strip().replace("﻿", "")).isEqualTo(header);
		}
	}

}
