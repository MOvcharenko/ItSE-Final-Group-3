package integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import persistence.SectionRepository;

/**
 * Stands in for the SIS (TP3, owner: Dev B).
 * Pre-configured with baseline dataset TD-1 (e.g. 92 students in ENG 101, 14 shared between MATH 210 and PHYS 110).
 * Supports graceful degradation and runtime overrides for testing.
 */
@Component
public class StubSisClient implements SisClient {

	private static final String JORDAN_ELLIS = "U03";

	private final SectionRepository sections;
	private volatile boolean available = true;

	private final Map<String, Integer> enrollments = new ConcurrentHashMap<>();
	private final Map<String, Integer> sharedStudents = new ConcurrentHashMap<>();

	public StubSisClient() {
		this(null);
	}

	@Autowired
	public StubSisClient(SectionRepository sections) {
		this.sections = sections;
		loadTd1Defaults();
	}

	public void loadTd1Defaults() {
		enrollments.clear();
		sharedStudents.clear();
		available = true;

		// TD-1 baseline enrollments
		setEnrollment("ENG 101", "01", 92);
		setEnrollment("ENG 101", 92);
		setEnrollment("SEC-103", 92);

		setEnrollment("MATH 210", "01", 25);
		setEnrollment("MATH 210", 25);
		setEnrollment("SEC-101", 25);

		setEnrollment("CS 201", "01", 20);
		setEnrollment("CS 201", 20);
		setEnrollment("SEC-102", 20);

		setEnrollment("BIO 150", "01", 30);
		setEnrollment("BIO 150", 30);
		setEnrollment("SEC-104", 30);

		setEnrollment("PHYS 110", "01", 24);
		setEnrollment("PHYS 110", 24);
		setEnrollment("SEC-105", 24);

		setEnrollment("CS 340", "01", 30);
		setEnrollment("CS 340", 30);
		setEnrollment("SEC-106", 30);

		setEnrollment("ART 220", "01", 20);
		setEnrollment("ART 220", 20);
		setEnrollment("SEC-107", 20);

		// Shared students between MATH 210 and PHYS 110 (TC-FR4.3f)
		setSharedStudents("MATH 210", "PHYS 110", 14);
		setSharedStudents("SEC-101", "SEC-105", 14);
	}

	@Override
	public List<String> enrolledStudentIds(String sectionId) {
		checkAvailable();
		return List.of(JORDAN_ELLIS);
	}

	@Override
	public Set<String> enrolledSectionIds(String studentId) {
		checkAvailable();
		if (!JORDAN_ELLIS.equals(studentId)) {
			return Set.of();
		}
		return sections != null ? sections.findAllIds() : Set.of("SEC-101", "SEC-103", "SEC-104");
	}

	@Override
	public int getEnrollmentCount(String courseCode, String sectionNumber) {
		checkAvailable();
		if (courseCode == null) {
			return 0;
		}
		String secKey = sectionNumber != null ? sectionNumber : "01";
		Integer count = enrollments.get(courseCode + ":" + secKey);
		if (count == null) {
			count = enrollments.get(courseCode);
		}
		return count != null ? count : 0;
	}

	@Override
	public int getEnrollmentCount(String sectionId) {
		checkAvailable();
		if (sectionId == null) {
			return 0;
		}
		Integer count = enrollments.get(sectionId);
		return count != null ? count : 0;
	}

	@Override
	public int getSharedStudents(String section1Id, String section2Id) {
		checkAvailable();
		if (section1Id == null || section2Id == null) {
			return 0;
		}
		String key = pairKey(section1Id, section2Id);
		Integer count = sharedStudents.get(key);
		return count != null ? count : 0;
	}

	@Override
	public boolean isAvailable() {
		return available;
	}

	public void setAvailable(boolean available) {
		this.available = available;
	}

	public void setEnrollment(String courseCode, String sectionNumber, int count) {
		enrollments.put(courseCode + ":" + sectionNumber, count);
	}

	public void setEnrollment(String key, int count) {
		enrollments.put(key, count);
	}

	public void setSharedStudents(String section1Id, String section2Id, int count) {
		sharedStudents.put(pairKey(section1Id, section2Id), count);
	}

	private void checkAvailable() {
		if (!available) {
			throw new SisUnavailableException("SIS is currently unreachable.");
		}
	}

	private String pairKey(String s1, String s2) {
		return s1.compareTo(s2) < 0 ? s1 + ":" + s2 : s2 + ":" + s1;
	}

}
