package api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import api.dto.AvailabilitySubmissionRequest;
import jakarta.validation.Valid;
import service.AvailabilityService;
import service.view.AvailabilityLensView;
import service.view.AvailabilityView;

/**
 * REST API for instructor availability declarations, equipment requests, and timetable lens (UC-14, FR1.4, NFR3.4).
 */
@RestController
public class AvailabilityController {

	private final AvailabilityService availability;

	public AvailabilityController(AvailabilityService availability) {
		this.availability = availability;
	}

	/**
	 * An instructor reviews their own availability, equipment requests, and hours summary for a term.
	 */
	@GetMapping("/api/terms/{id}/availability/me")
	@PreAuthorize("hasRole('INSTRUCTOR')")
	public AvailabilityView getMyAvailability(@PathVariable("id") String termId) {
		return availability.getMyAvailability(termId);
	}

	/**
	 * An instructor submits or updates their availability and equipment requests for a Draft term (NFR3.4).
	 */
	@PutMapping("/api/terms/{id}/availability/me")
	@PreAuthorize("hasRole('INSTRUCTOR')")
	public AvailabilityView submitMyAvailability(
			@PathVariable("id") String termId,
			@Valid @RequestBody AvailabilitySubmissionRequest request
	) {
		return availability.submitMyAvailability(termId, request);
	}

	/**
	 * Timetable builder overlay: retrieves an instructor's availability blocks for the term (FR1.4).
	 */
	@GetMapping("/api/instructors/{id}/availability")
	@PreAuthorize("hasAnyRole('SCHEDULER', 'INSTRUCTOR')")
	public AvailabilityLensView getInstructorAvailability(
			@PathVariable("id") String instructorId,
			@RequestParam("termId") String termId
	) {
		return availability.getInstructorAvailability(instructorId, termId);
	}
}
