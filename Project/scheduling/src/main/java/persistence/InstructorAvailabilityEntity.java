package persistence;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import domain.AvailabilityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One availability block of an instructor for a term (TR2.7, table instructor_availability).
 */
@Entity
@Table(name = "instructor_availability")
public class InstructorAvailabilityEntity extends AssignedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instructor_id", nullable = false)
	private AppUserEntity instructor;

	@Enumerated(EnumType.STRING)
	@Column(name = "day_of_week", nullable = false, length = 10)
	private DayOfWeek dayOfWeek;

	@Column(name = "start_time", nullable = false)
	private LocalTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalTime endTime;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 12)
	private AvailabilityStatus status;

	protected InstructorAvailabilityEntity() {
	}

	public InstructorAvailabilityEntity(String id, TermEntity term, AppUserEntity instructor,
			DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, AvailabilityStatus status) {
		super(id);
		this.term = term;
		this.instructor = instructor;
		this.dayOfWeek = dayOfWeek;
		this.startTime = startTime;
		this.endTime = endTime;
		this.status = status;
	}

	public static InstructorAvailabilityEntity create(TermEntity term, AppUserEntity instructor,
			DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, AvailabilityStatus status) {
		return new InstructorAvailabilityEntity(UUID.randomUUID().toString(), term, instructor,
				dayOfWeek, startTime, endTime, status);
	}

	public TermEntity getTerm() {
		return term;
	}

	public AppUserEntity getInstructor() {
		return instructor;
	}

	public DayOfWeek getDayOfWeek() {
		return dayOfWeek;
	}

	public LocalTime getStartTime() {
		return startTime;
	}

	public LocalTime getEndTime() {
		return endTime;
	}

	public AvailabilityStatus getStatus() {
		return status;
	}

	public void setStatus(AvailabilityStatus status) {
		this.status = status;
	}
}
