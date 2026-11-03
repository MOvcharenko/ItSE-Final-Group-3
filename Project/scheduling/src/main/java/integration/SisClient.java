package integration;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of the Student Information System (TR1.3, TR4.4). Owner: Dev B. A call may fail, and callers must
 * carry on without the data (UC-06 3a). The real client must set a short timeout, so a hung SIS fails instead of tying
 * up a thread. Dev B: add getEnrollmentCount and getSharedStudents here.
 */
public interface SisClient {

	/** Core Platform user ids of the students enrolled in a section. */
	List<String> enrolledStudentIds(String sectionId);

	/** The sections a student is enrolled in, by Core Platform user id. */
	Set<String> enrolledSectionIds(String studentId);

}
