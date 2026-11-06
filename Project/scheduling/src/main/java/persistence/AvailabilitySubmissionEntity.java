package persistence;

import java.io.Serializable;
import java.time.LocalDateTime;
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
 * Notes and submission timestamp for an instructor's availability in a term (UC-14).
 */
@Entity
@Table(name = "availability_submission")
@IdClass(AvailabilitySubmissionEntity.Pk.class)
public class AvailabilitySubmissionEntity {

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@Id
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instructor_id", nullable = false)
	private AppUserEntity instructor;

	@Column(name = "notes", length = 2000, nullable = true)
	private String notes;

	@Column(name = "submitted_at", nullable = false)
	private LocalDateTime submittedAt;

	protected AvailabilitySubmissionEntity() {
	}

	public AvailabilitySubmissionEntity(TermEntity term, AppUserEntity instructor, String notes, LocalDateTime submittedAt) {
		this.term = term;
		this.instructor = instructor;
		this.notes = notes;
		this.submittedAt = submittedAt;
	}

	public TermEntity getTerm() {
		return term;
	}

	public AppUserEntity getInstructor() {
		return instructor;
	}

	public String getNotes() {
		return notes;
	}

	public void setNotes(String notes) {
		this.notes = notes;
	}

	public LocalDateTime getSubmittedAt() {
		return submittedAt;
	}

	public void setSubmittedAt(LocalDateTime submittedAt) {
		this.submittedAt = submittedAt;
	}

	public static class Pk implements Serializable {
		private String term;
		private String instructor;

		public Pk() {
		}

		public Pk(String term, String instructor) {
			this.term = term;
			this.instructor = instructor;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Pk pk = (Pk) o;
			return Objects.equals(term, pk.term) && Objects.equals(instructor, pk.instructor);
		}

		@Override
		public int hashCode() {
			return Objects.hash(term, instructor);
		}
	}
}
