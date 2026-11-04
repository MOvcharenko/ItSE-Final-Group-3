package service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import domain.ResourceStatus;
import domain.RoomType;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import service.view.EquipmentView;
import service.view.RoomView;
import service.view.SubjectView;

/**
 * Subjects, Rooms and Equipment catalogs (FR2.1, FR2.2, FR3.1, FR3.2, FR2.5).
 * ImportService also adds rows through this class, one call (= one transaction) per row, so a bad row rolls back
 * only itself. That is why every rule lives here and not in the controller.
 */
@Service
@Transactional
public class CatalogServiceImpl implements CatalogService {

	private final SubjectRepository subjects;
	private final RoomRepository rooms;
	private final EquipmentRepository equipment;
	private final Caller caller;

	public CatalogServiceImpl(SubjectRepository subjects, RoomRepository rooms, EquipmentRepository equipment,
			Caller caller) {
		this.subjects = subjects;
		this.rooms = rooms;
		this.equipment = equipment;
		this.caller = caller;
	}

	// ---------------- subjects (Scheduler only) ----------------

	@Override
	@Transactional(readOnly = true)
	public List<SubjectView> listSubjects() {
		caller.requireScheduler();
		Set<String> scheduled = subjects.scheduledSubjectIds();
		return subjects.findAllByOrderByCourseCodeAsc().stream()
				.map(s -> Views.subject(s, scheduled.contains(s.getId()))).toList();
	}

	@Override
	public SubjectView addSubject(String courseCode, String title, int creditHours, RoomType defaultRoomType,
			String department, List<String> typicalEquipment) {
		caller.requireScheduler();
		String code = Check.text(courseCode, "Course code", 30);
		String name = Check.text(title, "Title", 200);
		Check.atLeastOne(creditHours, "Credit hours");
		Check.required(defaultRoomType, "Default room type");
		Set<String> items = Check.items(typicalEquipment, "Equipment entry", 100);
		if (subjects.existsByCourseCodeIgnoreCase(code)) {
			throw new BusinessRuleException("Duplicate subject code: '" + code + "' is already in the catalog.");
		}
		SubjectEntity saved = subjects.save(SubjectEntity.create(code, name, creditHours, defaultRoomType,
				resolveDepartment(department), items));
		return Views.subject(saved, false); // a new subject is "Not yet scheduled" (FR3.1)
	}

	/**
	 * The User's Guide says a subject needs only a code, title, credit hours and room type, so department is optional.
	 * Left blank: a Scheduler with exactly one authorized department gets it filled in; otherwise it stays empty
	 * and can be completed later. Given: the Scheduler must be authorized for it (TR3.4).
	 */
	private String resolveDepartment(String department) {
		String d = Check.optional(department, "Department", 100);
		if (d == null) {
			Set<String> mine = caller.departments();
			return mine.size() == 1 ? mine.iterator().next() : null;
		}
		String canonical = caller.canonicalDepartment(d);
		if (!caller.mayEdit(canonical)) {
			throw new AccessDeniedException("You are not authorized to add subjects to the department '" + d + "'.");
		}
		return canonical;
	}

	// ---------------- rooms ----------------

	@Override
	@Transactional(readOnly = true)
	public List<RoomView> listRooms() {
		caller.requireSchedulerOrInstructor();
		return rooms.findAllByOrderByNameAsc().stream().map(Views::room).toList();
	}

	@Override
	public RoomView addRoom(String name, String building, int capacity, RoomType roomType,
			List<String> fixedEquipment) {
		caller.requireScheduler();
		String n = Check.text(name, "Room name", 100);
		String b = Check.text(building, "Building", 100);
		Check.atLeastOne(capacity, "Capacity");
		Check.required(roomType, "Room type");
		Set<String> items = Check.items(fixedEquipment, "Equipment entry", 100);
		if (rooms.existsByNameIgnoreCase(n)) {
			throw new BusinessRuleException("Duplicate room name: '" + n + "' already exists.");
		}
		return Views.room(rooms.save(RoomEntity.create(n, b, capacity, roomType, new LinkedHashSet<>(items))));
	}

	@Override
	public RoomView setRoomStatus(String roomId, ResourceStatus status) {
		caller.requireScheduler();
		Check.required(status, "Status");
		RoomEntity room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("Room not found."));
		room.setStatus(status); // history is kept: nothing is deleted (FR2.5)
		return Views.room(room);
	}

	// ---------------- equipment ----------------

	@Override
	@Transactional(readOnly = true)
	public List<EquipmentView> listEquipment() {
		caller.requireSchedulerOrInstructor();
		return equipment.findAllByOrderByNameAsc().stream().map(e -> Views.equipment(e, null)).toList();
	}

	@Override
	public EquipmentView addEquipment(String name, String itemType, int unitCount, String homeLocation) {
		caller.requireScheduler();
		String n = Check.text(name, "Item name", 100);
		String type = Check.text(itemType, "Item type", 100);
		Check.atLeastOne(unitCount, "Unit count");
		String home = Check.optional(homeLocation, "Home location", 200);
		if (equipment.existsByNameIgnoreCase(n)) {
			throw new BusinessRuleException("Duplicate item name: '" + n + "' already exists.");
		}
		return Views.equipment(equipment.save(EquipmentEntity.create(n, type, unitCount, home)), null);
	}

	@Override
	public EquipmentView setEquipmentStatus(String equipmentId, ResourceStatus status) {
		caller.requireScheduler();
		Check.required(status, "Status");
		EquipmentEntity item = equipment.findById(equipmentId)
				.orElseThrow(() -> new NotFoundException("Equipment item not found."));
		item.setStatus(status);
		return Views.equipment(item, null);
	}
}
