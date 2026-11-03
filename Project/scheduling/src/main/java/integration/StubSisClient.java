package integration;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import persistence.SectionRepository;

/**
 * Stands in for the SIS (TP3): the test student Jordan Ellis is enrolled in every section, and nobody else in any.
 * TODO(Dev B): replace with per-section enrollment from TD-1 (92 students in ENG 101), and the real client later.
 */
@Component
public class StubSisClient implements SisClient {

	private static final String JORDAN_ELLIS = "U03";

	private final SectionRepository sections;

	public StubSisClient(SectionRepository sections) {
		this.sections = sections;
	}

	@Override
	public List<String> enrolledStudentIds(String sectionId) {
		return List.of(JORDAN_ELLIS);
	}

	@Override
	public Set<String> enrolledSectionIds(String studentId) {
		return JORDAN_ELLIS.equals(studentId) ? sections.findAllIds() : Set.of();
	}

}
