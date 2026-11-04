package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import domain.ResourceStatus;
import domain.RoomType;
import persistence.EquipmentEntity;
import persistence.EquipmentRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import security.CurrentUser;
import service.view.RoomView;
import service.view.SubjectView;

class CatalogServiceImplTest {

	SubjectRepository subjects = mock(SubjectRepository.class);
	RoomRepository rooms = mock(RoomRepository.class);
	EquipmentRepository equipment = mock(EquipmentRepository.class);

	@BeforeEach
	void saveReturnsItsArgument() {
		when(rooms.save(any(RoomEntity.class))).thenAnswer(i -> i.getArgument(0));
		when(subjects.save(any(SubjectEntity.class))).thenAnswer(i -> i.getArgument(0));
		when(equipment.save(any(EquipmentEntity.class))).thenAnswer(i -> i.getArgument(0));
	}

	private CatalogServiceImpl as(CurrentUser who) {
		return new CatalogServiceImpl(subjects, rooms, equipment, TestUsers.caller(who));
	}

	// ---------------- rooms ----------------

	@Test
	@DisplayName("TC-FR3.2: a room is added, trimmed, with a de-duplicated equipment list, and starts Available")
	void addRoom() {
		RoomView v = as(TestUsers.MAYA).addRoom("  Innovation Lab C ", "Innovation", 30, RoomType.LAB,
				List.of("Projector", "Projector", " ", "Whiteboard"));

		assertEquals("Innovation Lab C", v.name());
		assertEquals(List.of("Projector", "Whiteboard"), v.fixedEquipment());
		assertEquals(ResourceStatus.AVAILABLE, v.status());
		ArgumentCaptor<RoomEntity> saved = ArgumentCaptor.forClass(RoomEntity.class);
		verify(rooms).save(saved.capture());
		assertEquals(30, saved.getValue().getCapacity());
	}

	@Test
	@DisplayName("TC-FR3.2: capacity -5 (and 0) is refused with a plain message and nothing is saved")
	void nonPositiveCapacityIsRefused() {
		for (int bad : new int[] { -5, 0 }) {
			var e = assertThrows(BusinessRuleException.class,
					() -> as(TestUsers.MAYA).addRoom("Lab C", "Innovation", bad, RoomType.LAB, List.of()));
			assertEquals("Capacity must be at least 1.", e.getMessage());
		}
		verify(rooms, never()).save(any(RoomEntity.class));
	}

	@Test
	void duplicateRoomNameIsRefusedIgnoringCase() {
		when(rooms.existsByNameIgnoreCase("founders 110")).thenReturn(true);
		var e = assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addRoom("founders 110", "Founders", 60, RoomType.LECTURE_HALL, List.of()));
		assertTrue(e.getMessage().startsWith("Duplicate room name"), e.getMessage());
		verify(rooms, never()).save(any(RoomEntity.class));
	}

	@Test
	void missingFieldsAndTooLongValuesAreRefused() {
		assertEquals("Room name is required.", assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addRoom(" ", "B", 10, RoomType.LAB, List.of())).getMessage());
		assertEquals("Room type is required.", assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addRoom("R", "B", 10, null, List.of())).getMessage());
		assertTrue(assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addRoom("x".repeat(101), "B", 10, RoomType.LAB, List.of())).getMessage()
				.contains("too long"));
	}

	@Test
	@DisplayName("TR3.2 / TR3.3: only a Scheduler can add; Instructors and Students are refused and nothing is touched")
	void onlySchedulersCanAdd() {
		for (CurrentUser who : List.of(TestUsers.BIANCHI, TestUsers.STUDENT)) {
			assertThrows(AccessDeniedException.class,
					() -> as(who).addRoom("R", "B", 10, RoomType.LAB, List.of()));
			assertThrows(AccessDeniedException.class, () -> as(who).addEquipment("E", "T", 1, null));
			assertThrows(AccessDeniedException.class,
					() -> as(who).addSubject("X 1", "T", 3, RoomType.LAB, null, List.of()));
			assertThrows(AccessDeniedException.class, () -> as(who).setRoomStatus("R1", ResourceStatus.MAINTENANCE));
			assertThrows(AccessDeniedException.class, () -> as(who).listSubjects());
		}
		verifyNoMoreInteractions(rooms, subjects, equipment);
	}

	@Test
	@DisplayName("TR3.3: an Instructor may read rooms and equipment (to request bookings); a Student may not")
	void listingRoomsAndEquipment() {
		when(rooms.findAllByOrderByNameAsc()).thenReturn(List.of(
				new RoomEntity("R1", "Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of("Projector"))));
		assertEquals(1, as(TestUsers.BIANCHI).listRooms().size());
		assertEquals(1, as(TestUsers.MAYA).listRooms().size());
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.STUDENT).listRooms());
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.STUDENT).listEquipment());
	}

	@Test
	@DisplayName("FR2.5: Maintenance / Checked out are status changes on the same row; an unknown id is 'not found'")
	void changingStatus() {
		RoomEntity room = new RoomEntity("R1", "Founders 110", "Founders", 60, RoomType.LECTURE_HALL, Set.of());
		when(rooms.findById("R1")).thenReturn(Optional.of(room));

		RoomView v = as(TestUsers.MAYA).setRoomStatus("R1", ResourceStatus.MAINTENANCE);

		assertEquals(ResourceStatus.MAINTENANCE, v.status());
		assertEquals(ResourceStatus.MAINTENANCE, room.getStatus());
		verify(rooms, never()).delete(any(RoomEntity.class));
		assertThrows(NotFoundException.class, () -> as(TestUsers.MAYA).setRoomStatus("nope", ResourceStatus.AVAILABLE));
	}

	// ---------------- equipment ----------------

	@Test
	@DisplayName("TC-FR3.2: a portable speaker with 3 units and NO home location is accepted")
	void equipmentHomeLocationIsOptional() {
		var v = as(TestUsers.MAYA).addEquipment("Portable speaker", "Speaker", 3, "  ");
		assertNull(v.homeLocation());
		assertEquals(3, v.unitCount());
		assertNull(v.freeUnits());
	}

	@Test
	void equipmentNeedsAtLeastOneUnitAndAUniqueName() {
		assertEquals("Unit count must be at least 1.", assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addEquipment("Cart", "Laptop cart", 0, null)).getMessage());
		when(equipment.existsByNameIgnoreCase("Portable speaker")).thenReturn(true);
		assertTrue(assertThrows(BusinessRuleException.class,
				() -> as(TestUsers.MAYA).addEquipment("Portable speaker", "Speaker", 1, null)).getMessage()
				.startsWith("Duplicate item name"));
	}

	// ---------------- subjects ----------------

	@Test
	@DisplayName("TC-FR3.1: a new subject is 'Not yet scheduled'; the department is optional")
	void addSubjectIsNotScheduled() {
		SubjectView v = as(TestUsers.MAYA).addSubject("PSY 220", "Cognitive Psychology", 3, RoomType.LECTURE_HALL,
				null, List.of());
		assertFalse(v.scheduled());
		assertNull(v.department()); // Maya has two departments: nothing to guess
	}

	@Test
	@DisplayName("TR3.4: a blank department is filled in when the Scheduler has exactly one")
	void singleDepartmentIsDefaulted() {
		assertEquals("CS", as(TestUsers.MAYA_CS_ONLY).addSubject("CS 450", "Algorithms", 3, RoomType.LECTURE_HALL,
				"", List.of()).department());
	}

	@Test
	@DisplayName("TR3.4: adding a subject to a department the Scheduler is not authorized for is refused (403)")
	void departmentMustBeAuthorized() {
		assertThrows(AccessDeniedException.class, () -> as(TestUsers.MAYA).addSubject("HIST 120", "World History", 3,
				RoomType.LECTURE_HALL, "HIST", List.of()));
		// capitals do not matter when typing, and the stored spelling is the authorized one
		assertEquals("MATH", as(TestUsers.MAYA).addSubject("MATH 210", "Calculus", 4, RoomType.LECTURE_HALL,
				" math ", List.of()).department());
	}

	@Test
	@DisplayName("UC-12 6a: a duplicate subject code is refused, ignoring case")
	void duplicateSubjectCode() {
		when(subjects.existsByCourseCodeIgnoreCase("psy 220")).thenReturn(true);
		var e = assertThrows(BusinessRuleException.class, () -> as(TestUsers.MAYA).addSubject("psy 220", "Again", 3,
				RoomType.LAB, null, List.of()));
		assertTrue(e.getMessage().startsWith("Duplicate subject code"), e.getMessage());
	}

	@Test
	@DisplayName("FR3.4: the subject list marks which subjects already have a placed section")
	void listSubjectsMarksScheduled() {
		var hist = new SubjectEntity("S1", "HIST 120", "World History", 3, RoomType.LECTURE_HALL, "HIST", Set.of());
		var psy = new SubjectEntity("S2", "PSY 220", "Cognitive Psychology", 3, RoomType.LECTURE_HALL, null, Set.of());
		when(subjects.findAllByOrderByCourseCodeAsc()).thenReturn(List.of(hist, psy));
		when(subjects.scheduledSubjectIds()).thenReturn(Set.of("S1"));

		List<SubjectView> list = as(TestUsers.MAYA).listSubjects();

		assertTrue(list.get(0).scheduled());
		assertFalse(list.get(1).scheduled());
	}
}
