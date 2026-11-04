package api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.dto.AssignmentRequest;
import api.dto.TermNameRequest;
import api.dto.TermRequest;
import jakarta.validation.Valid;
import service.SectionPlacement;
import service.TimetableService;
import service.view.SectionView;
import service.view.TermView;

/**
 * Terms and sections (FR1). Only Schedulers change anything; the service also checks their departments (TR3.4).
 * Publishing with open High conflicts answers 409 until the caller repeats it with confirm=true (FR4.7).
 */
@RestController
@RequestMapping("/api")
public class TimetableController {

	private final TimetableService timetable;

	public TimetableController(TimetableService timetable) {
		this.timetable = timetable;
	}

	/** Who can be chosen when placing a section. */
	@GetMapping("/instructors")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<SectionView.InstructorView> listInstructors() {
		return timetable.listInstructors();
	}

	// ---- Terms ----

	@GetMapping("/terms")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public List<TermView> listTerms() {
		return timetable.listTerms();
	}

	@GetMapping("/terms/{id}")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public TermView getTerm(@PathVariable("id") String id) {
		return timetable.getTerm(id);
	}

	@PostMapping("/terms")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public TermView createTerm(@Valid @RequestBody TermRequest r) {
		return timetable.createTerm(r.name(), r.startDate(), r.endDate());
	}

	@PatchMapping("/terms/{id}")
	@PreAuthorize("hasRole('SCHEDULER')")
	public TermView renameTerm(@PathVariable("id") String id, @Valid @RequestBody TermNameRequest r) {
		return timetable.renameTerm(id, r.name());
	}

	@DeleteMapping("/terms/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('SCHEDULER')")
	public void deleteTerm(@PathVariable("id") String id) {
		timetable.deleteTerm(id);
	}

	@PostMapping("/terms/{id}/copy")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public TermView copyTerm(@PathVariable("id") String id, @Valid @RequestBody TermNameRequest r) {
		return timetable.copyTerm(id, r.name());
	}

	@PostMapping("/terms/{id}/publish")
	@PreAuthorize("hasRole('SCHEDULER')")
	public TermView publish(@PathVariable("id") String id,
			@RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
		return timetable.publish(id, confirm);
	}

	// ---- Sections ----

	@GetMapping("/terms/{id}/sections")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<SectionView> listSections(@PathVariable("id") String id) {
		return timetable.listSections(id);
	}

	@PostMapping("/terms/{id}/sections")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public SectionView placeSection(@PathVariable("id") String id, @RequestBody SectionPlacement placement) {
		return timetable.placeSection(id, placement);
	}

	@PutMapping("/terms/{id}/sections/{sectionId}")
	@PreAuthorize("hasRole('SCHEDULER')")
	public SectionView updateSection(@PathVariable("id") String id, @PathVariable("sectionId") String sectionId,
			@RequestBody SectionPlacement placement) {
		return timetable.updateSection(id, sectionId, placement);
	}

	@DeleteMapping("/terms/{id}/sections/{sectionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('SCHEDULER')")
	public void removeSection(@PathVariable("id") String id, @PathVariable("sectionId") String sectionId) {
		timetable.removeSection(id, sectionId);
	}

	@PatchMapping("/sections/{id}/assignment")
	@PreAuthorize("hasRole('SCHEDULER')")
	public SectionView changeAssignment(@PathVariable("id") String id, @RequestBody AssignmentRequest r) {
		return timetable.changeAssignment(id, r.roomId(), r.instructorIds());
	}
}
