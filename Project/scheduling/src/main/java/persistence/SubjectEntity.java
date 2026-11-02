package persistence;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import domain.RoomType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Subject catalog entry (TR2.2, table subject), independent of any term. Whether it is "Not yet scheduled" is not
 * stored: see SubjectRepository.scheduledSubjectIds().
 */
@Entity
@Table(name = "subject")
public class SubjectEntity extends AssignedIdEntity {

	@Column(name = "course_code", nullable = false, length = 30)
	private String courseCode;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "credit_hours", nullable = false)
	private int creditHours;

	@Enumerated(EnumType.STRING)
	@Column(name = "default_room_type", nullable = false, length = 20)
	private RoomType defaultRoomType;

	@Column(name = "department", length = 100)
	private String department;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "subject_equipment", joinColumns = @JoinColumn(name = "subject_id"))
	@Column(name = "item_type", nullable = false, length = 100)
	private Set<String> typicalEquipment = new LinkedHashSet<>();

	protected SubjectEntity() {
	}

	public SubjectEntity(String id, String courseCode, String title, int creditHours, RoomType defaultRoomType,
			String department, Set<String> typicalEquipment) {
		super(id);
		this.courseCode = courseCode;
		this.title = title;
		this.creditHours = creditHours;
		this.defaultRoomType = defaultRoomType;
		this.department = department;
		this.typicalEquipment = new LinkedHashSet<>(typicalEquipment);
	}

	public static SubjectEntity create(String courseCode, String title, int creditHours, RoomType defaultRoomType,
			String department, Set<String> typicalEquipment) {
		return new SubjectEntity(UUID.randomUUID().toString(), courseCode, title, creditHours, defaultRoomType,
				department, typicalEquipment);
	}

	public String getCourseCode() {
		return courseCode;
	}

	public String getTitle() {
		return title;
	}

	public int getCreditHours() {
		return creditHours;
	}

	public RoomType getDefaultRoomType() {
		return defaultRoomType;
	}

	public String getDepartment() {
		return department;
	}

	public Set<String> getTypicalEquipment() {
		return typicalEquipment;
	}
}
