package api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import api.dto.DismissConflictRequest;
import api.dto.ReassignRequest;
import domain.ConflictType;
import domain.ResolutionState;
import domain.Severity;
import jakarta.validation.Valid;
import service.ConflictService;
import service.view.ConflictView;
import service.view.DismissalView;

/**
 * REST API for reviewing and resolving schedule conflicts (FR4, UC-05, UC-06).
 * Owner: Dev B. All endpoints require the SCHEDULER role.
 */
@RestController
public class ConflictController {

	private final ConflictService conflicts;

	public ConflictController(ConflictService conflicts) {
		this.conflicts = conflicts;
	}

	@GetMapping("/api/terms/{id}/conflicts")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<ConflictView> listConflicts(
			@PathVariable("id") String termId,
			@RequestParam(name = "type", required = false) ConflictType type,
			@RequestParam(name = "severity", required = false) Severity severity,
			@RequestParam(name = "status", required = false) ResolutionState status
	) {
		return conflicts.listConflicts(termId, type, severity, status);
	}

	/** Who dismissed which of the term's conflicts, when and why, newest first (NFR3.3). */
	@GetMapping("/api/terms/{id}/dismissals")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<DismissalView> listDismissals(@PathVariable("id") String termId) {
		return conflicts.listDismissals(termId);
	}

	@PostMapping("/api/terms/{id}/rescan")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<ConflictView> rescan(@PathVariable("id") String termId) {
		return conflicts.rescan(termId);
	}

	@PostMapping("/api/conflicts/{id}/reassign")
	@PreAuthorize("hasRole('SCHEDULER')")
	public ConflictView reassign(
			@PathVariable("id") String conflictId,
			@Valid @RequestBody ReassignRequest request
	) {
		return conflicts.reassign(conflictId, request);
	}

	@PostMapping("/api/conflicts/{id}/dismiss")
	@PreAuthorize("hasRole('SCHEDULER')")
	public ConflictView dismiss(
			@PathVariable("id") String conflictId,
			@Valid @RequestBody DismissConflictRequest request
	) {
		return conflicts.dismiss(conflictId, request.reason());
	}
}
