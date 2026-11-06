package persistence;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Portable equipment an instructor requests for their courses in a term (UC-14).
 */
@Entity
@Table(name = "instructor_equipment_need")
@IdClass(InstructorEquipmentNeedEntity.Pk.class)
public class InstructorEquipmentNeedEntity {

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instructor_id", nullable = false)
	private AppUserEntity instructor;

	@Id
	@Column(name = "item_type", length = 100, nullable = false)
	private String itemType;

	protected InstructorEquipmentNeedEntity() {
	}

	public InstructorEquipmentNeedEntity(TermEntity term, AppUserEntity instructor, String itemType) {
		this.term = term;
		this.instructor = instructor;
		this.itemType = itemType;
	}

	public TermEntity getTerm() {
		return term;
	}

	public AppUserEntity getInstructor() {
		return instructor;
	}

	public String getItemType() {
		return itemType;
	}

	public static class Pk implements Serializable {
		private String term;
		private String instructor;
		private String itemType;

		public Pk() {
		}

		public Pk(String term, String instructor, String itemType) {
			this.term = term;
			this.instructor = instructor;
			this.itemType = itemType;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Pk pk = (Pk) o;
			return Objects.equals(term, pk.term) && Objects.equals(instructor, pk.instructor) && Objects.equals(itemType, pk.itemType);
		}

		@Override
		public int hashCode() {
			return Objects.hash(term, instructor, itemType);
		}
	}
}
