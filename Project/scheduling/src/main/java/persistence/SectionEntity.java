package persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import domain.EquipmentNeed;
import domain.MeetingSlot;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A scheduled instance of a Subject in one Term (TR2.3, table section), with its meetings, instructors and
 * equipment needs. Enrollment is not stored: the SIS owns it. Conflict detection reads sections through
 * {@link SectionRepository#findForTerm}; the collections are lazy, so read them inside a transaction.
 */
@Entity
@Table(name = "section")
public class SectionEntity extends AssignedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "subject_id", nullable = false)
	private SubjectEntity subject;

	@Column(name = "section_number", nullable = false, length = 10)
	private String sectionNumber;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "room_id")
	private RoomEntity room;

	@ElementCollection
	@CollectionTable(name = "section_meeting", joinColumns = @JoinColumn(name = "section_id"))
	private List<SectionMeeting> meetings = new ArrayList<>();

	@ElementCollection
	@CollectionTable(name = "section_instructor", joinColumns = @JoinColumn(name = "section_id"))
	@Column(name = "instructor_id", nullable = false, length = 100)
	private Set<String> instructorIds = new LinkedHashSet<>();

	@ElementCollection
	@CollectionTable(name = "section_equipment", joinColumns = @JoinColumn(name = "section_id"))
	private List<SectionEquipment> equipment = new ArrayList<>();

	protected SectionEntity() {
	}

	public SectionEntity(String id, TermEntity term, SubjectEntity subject, String sectionNumber) {
		super(id);
		this.term = term;
		this.subject = subject;
		this.sectionNumber = sectionNumber;
	}

	public static SectionEntity create(TermEntity term, SubjectEntity subject, String sectionNumber) {
		return new SectionEntity(UUID.randomUUID().toString(), term, subject, sectionNumber);
	}

	/** Same subject, placement, instructors and equipment in another term (FR1.1). */
	public SectionEntity copyTo(TermEntity targetTerm) {
		SectionEntity copy = create(targetTerm, subject, sectionNumber);
		copy.setRoom(room);
		copy.setMeetings(getMeetings());
		copy.setInstructorIds(instructorIds);
		copy.setEquipment(getEquipment());
		return copy;
	}

	public TermEntity getTerm() {
		return term;
	}

	public SubjectEntity getSubject() {
		return subject;
	}

	public String getSectionNumber() {
		return sectionNumber;
	}

	public RoomEntity getRoom() {
		return room;
	}

	public void setRoom(RoomEntity room) {
		this.room = room;
	}

	public List<MeetingSlot> getMeetings() {
		return meetings.stream().map(SectionMeeting::toSlot).toList();
	}

	public void setMeetings(Collection<MeetingSlot> slots) {
		meetings.clear();
		slots.forEach(slot -> meetings.add(new SectionMeeting(slot)));
	}

	public Set<String> getInstructorIds() {
		return Set.copyOf(instructorIds);
	}

	public void setInstructorIds(Collection<String> ids) {
		instructorIds.clear();
		instructorIds.addAll(ids);
	}

	public List<EquipmentNeed> getEquipment() {
		return equipment.stream().map(SectionEquipment::toNeed).toList();
	}

	public void setEquipment(Collection<EquipmentNeed> needs) {
		equipment.clear();
		needs.forEach(need -> equipment.add(new SectionEquipment(need)));
	}
}
