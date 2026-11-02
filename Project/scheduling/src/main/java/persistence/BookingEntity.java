package persistence;

import java.time.LocalDateTime;
import java.util.UUID;

import domain.BookingStatus;
import domain.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One request in the shared room/equipment queue (TR2.6, TR4.5, table booking). It points at exactly one resource:
 * the two factory methods are the only way to build one, and the database CHECK enforces the same rule.
 * Associations are LAZY; the BookingRepository queries that feed the queue use JOIN FETCH so listing is one query.
 */
@Entity
@Table(name = "booking")
public class BookingEntity extends AssignedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requester_id", nullable = false)
	private AppUserEntity requester;

	@Enumerated(EnumType.STRING)
	@Column(name = "resource_type", nullable = false, length = 10)
	private ResourceType resourceType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "room_id")
	private RoomEntity room;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "equipment_id")
	private EquipmentEntity equipment;

	@Column(name = "start_time", nullable = false)
	private LocalDateTime startTime;

	@Column(name = "end_time", nullable = false)
	private LocalDateTime endTime;

	@Column(name = "purpose", nullable = false, length = 500)
	private String purpose;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private BookingStatus status = BookingStatus.PENDING;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "decided_by", length = 100)
	private String decidedBy;

	@Column(name = "decided_at")
	private LocalDateTime decidedAt;

	@Column(name = "cancel_note", length = 500)
	private String cancelNote;

	protected BookingEntity() {
	}

	private BookingEntity(String id, AppUserEntity requester, ResourceType type, LocalDateTime start,
			LocalDateTime end, String purpose, LocalDateTime createdAt) {
		super(id);
		this.requester = requester;
		this.resourceType = type;
		this.startTime = start;
		this.endTime = end;
		this.purpose = purpose;
		this.createdAt = createdAt;
	}

	public static BookingEntity forRoom(AppUserEntity requester, RoomEntity room, LocalDateTime start,
			LocalDateTime end, String purpose, LocalDateTime createdAt) {
		BookingEntity b = new BookingEntity(UUID.randomUUID().toString(), requester, ResourceType.ROOM, start, end,
				purpose, createdAt);
		b.room = room;
		return b;
	}

	public static BookingEntity forEquipment(AppUserEntity requester, EquipmentEntity equipment, LocalDateTime start,
			LocalDateTime end, String purpose, LocalDateTime createdAt) {
		BookingEntity b = new BookingEntity(UUID.randomUUID().toString(), requester, ResourceType.EQUIPMENT, start,
				end, purpose, createdAt);
		b.equipment = equipment;
		return b;
	}

	/** The service has already checked the booking is PENDING and that no approved booking overlaps (NFR3.1). */
	public void markApproved(String deciderId, LocalDateTime at) {
		this.status = BookingStatus.APPROVED;
		this.decidedBy = deciderId;
		this.decidedAt = at;
	}

	public void markCancelled(String deciderId, LocalDateTime at, String note) {
		this.status = BookingStatus.CANCELLED;
		this.decidedBy = deciderId;
		this.decidedAt = at;
		this.cancelNote = note;
	}

	/** Id of whichever resource this booking holds. */
	public String resourceId() {
		return resourceType == ResourceType.ROOM ? room.getId() : equipment.getId();
	}

	public String resourceName() {
		return resourceType == ResourceType.ROOM ? room.getName() : equipment.getName();
	}

	public AppUserEntity getRequester() {
		return requester;
	}

	public ResourceType getResourceType() {
		return resourceType;
	}

	public RoomEntity getRoom() {
		return room;
	}

	public EquipmentEntity getEquipment() {
		return equipment;
	}

	public LocalDateTime getStartTime() {
		return startTime;
	}

	public LocalDateTime getEndTime() {
		return endTime;
	}

	public String getPurpose() {
		return purpose;
	}

	public BookingStatus getStatus() {
		return status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public String getDecidedBy() {
		return decidedBy;
	}

	public LocalDateTime getDecidedAt() {
		return decidedAt;
	}

	public String getCancelNote() {
		return cancelNote;
	}
}
