package api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.dto.EquipmentRequest;
import api.dto.ResourceStatusRequest;
import api.dto.RoomRequest;
import api.dto.SubjectRequest;
import jakarta.validation.Valid;
import service.CatalogService;
import service.view.EquipmentView;
import service.view.RoomView;
import service.view.SubjectView;

/**
 * Subjects, Rooms and Equipment catalogs. Thin by design: bind, validate, delegate (TR1.1).
 * Roles (TR3): Schedulers read and write everything; Instructors may only read rooms and equipment
 * (they need them to request bookings); Students get 403 on all of it (TR3.2).
 * The service layer re-checks the caller, so a missing annotation here is not the only line of defence.
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

	private final CatalogService catalog;

	public CatalogController(CatalogService catalog) {
		this.catalog = catalog;
	}

	// ---- Subjects (Scheduler only) ----

	@GetMapping("/subjects")
	@PreAuthorize("hasRole('SCHEDULER')")
	public List<SubjectView> listSubjects() {
		return catalog.listSubjects();
	}

	@PostMapping("/subjects")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public SubjectView addSubject(@Valid @RequestBody SubjectRequest r) {
		return catalog.addSubject(r.courseCode(), r.title(), r.creditHours(), r.defaultRoomType(),
				r.department(), r.typicalEquipment());
	}

	// ---- Rooms ----

	@GetMapping("/rooms")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public List<RoomView> listRooms() {
		return catalog.listRooms();
	}

	@PostMapping("/rooms")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public RoomView addRoom(@Valid @RequestBody RoomRequest r) {
		return catalog.addRoom(r.name(), r.building(), r.capacity(), r.roomType(), r.fixedEquipment());
	}

	@PatchMapping("/rooms/{id}/status")
	@PreAuthorize("hasRole('SCHEDULER')")
	public RoomView setRoomStatus(@PathVariable("id") String id, @Valid @RequestBody ResourceStatusRequest r) {
		return catalog.setRoomStatus(id, r.status());
	}

	// ---- Equipment ----

	@GetMapping("/equipment")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public List<EquipmentView> listEquipment() {
		return catalog.listEquipment();
	}

	@PostMapping("/equipment")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('SCHEDULER')")
	public EquipmentView addEquipment(@Valid @RequestBody EquipmentRequest r) {
		return catalog.addEquipment(r.name(), r.itemType(), r.unitCount(), r.homeLocation());
	}

	@PatchMapping("/equipment/{id}/status")
	@PreAuthorize("hasRole('SCHEDULER')")
	public EquipmentView setEquipmentStatus(@PathVariable("id") String id,
			@Valid @RequestBody ResourceStatusRequest r) {
		return catalog.setEquipmentStatus(id, r.status());
	}
}
