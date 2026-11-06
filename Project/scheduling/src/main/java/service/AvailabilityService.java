package service;

import api.dto.AvailabilitySubmissionRequest;
import service.view.AvailabilityLensView;
import service.view.AvailabilityView;

/**
 * Service managing instructor availability declarations, equipment needs, and scheduling lens (UC-14, FR1.4, NFR3.4).
 */
public interface AvailabilityService {

	/**
	 * Returns the caller instructor's declared availability, equipment needs, and summary for the term.
	 */
	AvailabilityView getMyAvailability(String termId);

	/**
	 * Saves or updates the caller instructor's availability blocks and equipment requests for a Draft term.
	 * Fails with BusinessRuleException if the term is PUBLISHED (NFR3.4).
	 */
	AvailabilityView submitMyAvailability(String termId, AvailabilitySubmissionRequest request);

	/**
	 * Returns the availability lens for an instructor in a term for Timetable Builder overlay (FR1.4).
	 */
	AvailabilityLensView getInstructorAvailability(String instructorId, String termId);
}
