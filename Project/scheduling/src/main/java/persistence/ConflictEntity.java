package persistence;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import domain.ConflictType;
import domain.ResolutionState;
import domain.Severity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A detected schedule conflict (TR2.8, table conflict).
 * Uniqueness on (term_id, fingerprint) ensures that re-scans do not insert duplicate rows,
 * and DISMISSED conflicts do not come back as fresh OPEN ones.
 */
@Entity
@Table(name = "conflict")
public class ConflictEntity extends AssignedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "term_id", nullable = false)
	private TermEntity term;

	@Enumerated(EnumType.STRING)
	@Column(name = "conflict_type", nullable = false, length = 30)
	private ConflictType conflictType;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 10)
	private Severity severity;

	@Column(name = "explanation", nullable = false, length = 1000)
	private String explanation;

	@Enumerated(EnumType.STRING)
	@Column(name = "resolution_state", nullable = false, length = 10)
	private ResolutionState resolutionState = ResolutionState.OPEN;

	@Column(name = "fingerprint", nullable = false, length = 255)
	private String fingerprint;

	@Column(name = "detected_at", nullable = false)
	private LocalDateTime detectedAt;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "room_id")
	private RoomEntity room;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "instructor_id")
	private AppUserEntity instructor;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "equipment_id")
	private EquipmentEntity equipment;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "booking_id")
	private BookingEntity booking;

	@ElementCollection
	@CollectionTable(name = "conflict_section", joinColumns = @JoinColumn(name = "conflict_id"))
	@Column(name = "section_id", nullable = false, length = 36)
	private Set<String> sectionIds = new LinkedHashSet<>();

	protected ConflictEntity() {
	}

	public ConflictEntity(String id, TermEntity term, ConflictType conflictType, Severity severity,
			String explanation, String fingerprint, LocalDateTime detectedAt) {
		super(id);
		this.term = term;
		this.conflictType = conflictType;
		this.severity = severity;
		this.explanation = explanation;
		this.fingerprint = fingerprint;
		this.detectedAt = detectedAt;
		this.resolutionState = ResolutionState.OPEN;
	}

	public static ConflictEntity create(TermEntity term, ConflictType conflictType, Severity severity,
			String explanation, String fingerprint, LocalDateTime detectedAt) {
		return new ConflictEntity(UUID.randomUUID().toString(), term, conflictType, severity,
				explanation, fingerprint, detectedAt);
	}

	public TermEntity getTerm() {
		return term;
	}

	public ConflictType getConflictType() {
		return conflictType;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getExplanation() {
		return explanation;
	}

	public void setExplanation(String explanation) {
		this.explanation = explanation;
	}

	public ResolutionState getResolutionState() {
		return resolutionState;
	}

	public void setResolutionState(ResolutionState resolutionState) {
		this.resolutionState = resolutionState;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public LocalDateTime getDetectedAt() {
		return detectedAt;
	}

	public void setDetectedAt(LocalDateTime detectedAt) {
		this.detectedAt = detectedAt;
	}

	public LocalDateTime getResolvedAt() {
		return resolvedAt;
	}

	public void setResolvedAt(LocalDateTime resolvedAt) {
		this.resolvedAt = resolvedAt;
	}

	public RoomEntity getRoom() {
		return room;
	}

	public void setRoom(RoomEntity room) {
		this.room = room;
	}

	public AppUserEntity getInstructor() {
		return instructor;
	}

	public void setInstructor(AppUserEntity instructor) {
		this.instructor = instructor;
	}

	public EquipmentEntity getEquipment() {
		return equipment;
	}

	public void setEquipment(EquipmentEntity equipment) {
		this.equipment = equipment;
	}

	public BookingEntity getBooking() {
		return booking;
	}

	public void setBooking(BookingEntity booking) {
		this.booking = booking;
	}

	public Set<String> getSectionIds() {
		return sectionIds;
	}

	public void setSectionIds(Collection<String> sectionIds) {
		this.sectionIds = new LinkedHashSet<>(sectionIds);
	}

	public void addSectionId(String sectionId) {
		this.sectionIds.add(sectionId);
	}

	public boolean isOpen() {
		return resolutionState == ResolutionState.OPEN;
	}

	public boolean isDismissed() {
		return resolutionState == ResolutionState.DISMISSED;
	}

	public void resolve(LocalDateTime at) {
		this.resolutionState = ResolutionState.RESOLVED;
		this.resolvedAt = at;
	}

	public void reopen(LocalDateTime at) {
		this.resolutionState = ResolutionState.OPEN;
		this.detectedAt = at;
		this.resolvedAt = null;
	}

	public void dismiss() {
		this.resolutionState = ResolutionState.DISMISSED;
	}
}
