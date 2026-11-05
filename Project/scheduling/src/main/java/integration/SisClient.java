package integration;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of the Student Information System (TR1.3, TR4.4). Owner: Dev B. A call may fail, and callers must
 * carry on without the data (UC-06 3a). The real client must set a short timeout, so a hung SIS fails instead of tying
 * up a thread.
 */
public interface SisClient {

	/** Core Platform user ids of the students enrolled in a section. */
	List<String> enrolledStudentIds(String sectionId);

	/** The sections a student is enrolled in, by Core Platform user id. */
	Set<String> enrolledSectionIds(String studentId);

	/** Returns enrollment count for a course section by code and number (e.g. "ENG 101", "01"). */
	int getEnrollmentCount(String courseCode, String sectionNumber);

	/** Returns enrollment count for a section by section ID (e.g. "SEC-103"). */
	int getEnrollmentCount(String sectionId);

	/** Returns the number of students shared between two sections (for student-group clash rule, FR4.3f). */
	int getSharedStudents(String section1Id, String section2Id);

	/** Checks whether the SIS is reachable. */
	boolean isAvailable();

}

