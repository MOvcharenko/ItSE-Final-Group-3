package persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import domain.InsightAxis;
import domain.InsightMeasure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Advisory optimization suggestion regarding instructor utilization or room capacity (TR2.9, table insight_suggestion).
 * Visually distinguishable from conflicts (NFR4.4) and dismissible without schedule changes (FR5.3).
 */
@Entity
@Table(name = "insight_suggestion")
public class InsightSuggestionEntity extends AssignedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@Enumerated(EnumType.STRING)
	@Column(name = "axis", nullable = false, length = 25)
	private InsightAxis axis;

	@Enumerated(EnumType.STRING)
	@Column(name = "measure", nullable = true, length = 10)
	private InsightMeasure measure;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "instructor_id", nullable = true)
	private AppUserEntity instructor;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "room_id", nullable = true)
	private RoomEntity room;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "related_conflict_id", nullable = true)
	private ConflictEntity relatedConflict;

	@Column(name = "message", nullable = false, length = 1000)
	private String message;

	@Column(name = "dismissed", nullable = false)
	private boolean dismissed;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	protected InsightSuggestionEntity() {
	}

	public InsightSuggestionEntity(
			String id,
			TermEntity term,
			InsightAxis axis,
			InsightMeasure measure,
			AppUserEntity instructor,
			RoomEntity room,
			ConflictEntity relatedConflict,
			String message,
			boolean dismissed,
			LocalDateTime createdAt
	) {
		super(id);
		this.term = term;
		this.axis = axis;
		this.measure = measure;
		this.instructor = instructor;
		this.room = room;
		this.relatedConflict = relatedConflict;
		this.message = message;
		this.dismissed = dismissed;
		this.createdAt = createdAt;
	}

	public static InsightSuggestionEntity createInstructorSuggestion(
			TermEntity term,
			AppUserEntity instructor,
			ConflictEntity relatedConflict,
			String message,
			LocalDateTime createdAt
	) {
		return new InsightSuggestionEntity(
				UUID.randomUUID().toString(),
				term,
				InsightAxis.INSTRUCTOR_UTILIZATION,
				null,
				instructor,
				null,
				relatedConflict,
				message,
				false,
				createdAt
		);
	}

	public static InsightSuggestionEntity createRoomSuggestion(
			TermEntity term,
			InsightMeasure measure,
			RoomEntity room,
			ConflictEntity relatedConflict,
			String message,
			LocalDateTime createdAt
	) {
		return new InsightSuggestionEntity(
				UUID.randomUUID().toString(),
				term,
				InsightAxis.ROOM_CAPACITY,
				measure,
				null,
				room,
				relatedConflict,
				message,
				false,
				createdAt
		);
	}

	public TermEntity getTerm() {
		return term;
	}

	public InsightAxis getAxis() {
		return axis;
	}

	public InsightMeasure getMeasure() {
		return measure;
	}

	public AppUserEntity getInstructor() {
		return instructor;
	}

	public RoomEntity getRoom() {
		return room;
	}

	public ConflictEntity getRelatedConflict() {
		return relatedConflict;
	}

	public void setRelatedConflict(ConflictEntity relatedConflict) {
		this.relatedConflict = relatedConflict;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public boolean isDismissed() {
		return dismissed;
	}

	public void dismiss() {
		this.dismissed = true;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
