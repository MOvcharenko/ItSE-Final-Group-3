package persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import domain.TermStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** Academic term (TR2.1, table term). Always starts as a Draft; publishing is one way. */
@Entity
@Table(name = "term")
public class TermEntity extends AssignedIdEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private TermStatus status = TermStatus.DRAFT;

	@Column(name = "published_at")
	private LocalDateTime publishedAt;

	protected TermEntity() {
	}

	public TermEntity(String id, String name, LocalDate startDate, LocalDate endDate) {
		super(id);
		this.name = name;
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public static TermEntity create(String name, LocalDate startDate, LocalDate endDate) {
		return new TermEntity(UUID.randomUUID().toString(), name, startDate, endDate);
	}

	public void rename(String newName) {
		this.name = newName;
	}

	public void changeDates(LocalDate newStart, LocalDate newEnd) {
		this.startDate = newStart;
		this.endDate = newEnd;
	}

	public void publish(LocalDateTime at) {
		this.status = TermStatus.PUBLISHED;
		this.publishedAt = at;
	}

	public boolean isPublished() {
		return status == TermStatus.PUBLISHED;
	}

	public String getName() {
		return name;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public TermStatus getStatus() {
		return status;
	}

	public LocalDateTime getPublishedAt() {
		return publishedAt;
	}
}
