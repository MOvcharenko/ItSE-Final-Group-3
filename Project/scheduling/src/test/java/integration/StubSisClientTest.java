package integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubSisClientTest {

	@Test
	@DisplayName("StubSisClient initializes with TD-1 defaults: ENG 101 enrollment = 92")
	void td1Defaults() {
		StubSisClient sis = new StubSisClient();

		assertThat(sis.isAvailable()).isTrue();
		assertThat(sis.getEnrollmentCount("ENG 101", "01")).isEqualTo(92);
		assertThat(sis.getEnrollmentCount("SEC-103")).isEqualTo(92);
		assertThat(sis.getSharedStudents("MATH 210", "PHYS 110")).isEqualTo(14);
		assertThat(sis.getSharedStudents("PHYS 110", "MATH 210")).isEqualTo(14); // canonical pair
		List<String> studentIds = sis.enrolledStudentIds("SEC-103");
		assertThat(studentIds).contains("U03");
	}

	@Test
	@DisplayName("StubSisClient throws SisUnavailableException when marked unavailable")
	void unavailableThrows() {
		StubSisClient sis = new StubSisClient();
		sis.setAvailable(false);

		assertThat(sis.isAvailable()).isFalse();
		assertThatThrownBy(() -> sis.getEnrollmentCount("ENG 101", "01"))
				.isInstanceOf(SisUnavailableException.class);
		assertThatThrownBy(() -> sis.getSharedStudents("MATH 210", "PHYS 110"))
				.isInstanceOf(SisUnavailableException.class);
	}

	@Test
	@DisplayName("Overrides allow testing dynamic enrollments e.g. 80, 81, 92")
	void overrides() {
		StubSisClient sis = new StubSisClient();
		sis.setEnrollment("ENG 101", "01", 80);
		assertThat(sis.getEnrollmentCount("ENG 101", "01")).isEqualTo(80);

		sis.setEnrollment("ENG 101", "01", 81);
		assertThat(sis.getEnrollmentCount("ENG 101", "01")).isEqualTo(81);
	}
}
