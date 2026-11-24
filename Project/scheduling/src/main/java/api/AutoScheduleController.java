package api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import api.dto.AutoScheduleApplyRequest;
import api.dto.AutoScheduleRequest;
import jakarta.validation.Valid;
import service.AutoScheduleService;
import service.view.AutoScheduleView;
import service.view.SectionView;

/**
 * REST endpoints for SAT/MaxSAT automated schedule generation on Draft terms (Dev B).
 */
@RestController
@RequestMapping("/api/terms/{id}/auto-schedule")
public class AutoScheduleController {

	private final AutoScheduleService autoSchedule;

	public AutoScheduleController(AutoScheduleService autoSchedule) {
		this.autoSchedule = autoSchedule;
	}

	@PostMapping("/preview")
	@PreAuthorize("hasRole('SCHEDULER')")
	public AutoScheduleView preview(
			@PathVariable("id") String id,
			@RequestBody(required = false) AutoScheduleRequest request
	) {
		return autoSchedule.preview(id, request);
	}

	@PostMapping("/apply")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<SectionView> apply(
			@PathVariable("id") String id,
			@Valid @RequestBody AutoScheduleApplyRequest request
	) {
		return autoSchedule.apply(id, request);
	}
}
