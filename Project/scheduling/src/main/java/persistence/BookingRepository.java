package persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import domain.BookingStatus;

/**
 * Two windows overlap when {@code a.start < b.end AND b.start < a.end}: a booking that ends at 17:00 does not block
 * one that starts at 17:00 (TC-FR2.4). Only APPROVED bookings reserve a resource; PENDING ones do not.
 *
 * NOT covered here: rooms and equipment used by timetable sections (section_meeting). The search service must also
 * exclude those once the timetable stream's entities exist, or a room taught in every Monday morning shows as free.
 */
public interface BookingRepository extends JpaRepository<BookingEntity, String> {

	String QUEUE_FETCH = "select b from BookingEntity b join fetch b.requester left join fetch b.room "
			+ "left join fetch b.equipment ";

	// ---- approval check (call after taking the resource row lock) ----

	@Query("select count(b) from BookingEntity b where b.room.id = :roomId and b.status = :status "
			+ "and b.startTime < :windowEnd and :windowStart < b.endTime")
	long countForRoomInWindow(@Param("roomId") String roomId, @Param("status") BookingStatus status,
			@Param("windowStart") LocalDateTime windowStart, @Param("windowEnd") LocalDateTime windowEnd);

	@Query("select count(b) from BookingEntity b where b.equipment.id = :equipmentId and b.status = :status "
			+ "and b.startTime < :windowEnd and :windowStart < b.endTime")
	long countForEquipmentInWindow(@Param("equipmentId") String equipmentId, @Param("status") BookingStatus status,
			@Param("windowStart") LocalDateTime windowStart, @Param("windowEnd") LocalDateTime windowEnd);

	default long countApprovedForRoom(String roomId, LocalDateTime start, LocalDateTime end) {
		return countForRoomInWindow(roomId, BookingStatus.APPROVED, start, end);
	}

	/** A room has capacity 1; an equipment item has unitCount units, so compare this count with unitCount. */
	default long countApprovedForEquipment(String equipmentId, LocalDateTime start, LocalDateTime end) {
		return countForEquipmentInWindow(equipmentId, BookingStatus.APPROVED, start, end);
	}

	// ---- availability search (FR2.4) ----

	@Query("select distinct b.room.id from BookingEntity b where b.room is not null and b.status = :status "
			+ "and b.startTime < :windowEnd and :windowStart < b.endTime")
	List<String> roomIdsInWindow(@Param("status") BookingStatus status, @Param("windowStart") LocalDateTime windowStart,
			@Param("windowEnd") LocalDateTime windowEnd);

	default List<String> busyRoomIds(LocalDateTime start, LocalDateTime end) {
		return roomIdsInWindow(BookingStatus.APPROVED, start, end);
	}

	/** Units of each equipment item already approved in the window; free units = unitCount - used. */
	interface EquipmentUsage {
		String getEquipmentId();

		long getUsed();
	}

	@Query("select b.equipment.id as equipmentId, count(b) as used from BookingEntity b "
			+ "where b.equipment is not null and b.status = :status "
			+ "and b.startTime < :windowEnd and :windowStart < b.endTime group by b.equipment.id")
	List<EquipmentUsage> equipmentUsageInWindow(@Param("status") BookingStatus status,
			@Param("windowStart") LocalDateTime windowStart, @Param("windowEnd") LocalDateTime windowEnd);

	default List<EquipmentUsage> approvedEquipmentUsage(LocalDateTime start, LocalDateTime end) {
		return equipmentUsageInWindow(BookingStatus.APPROVED, start, end);
	}

	// ---- the shared queue (TR4.5): requester, room and equipment fetched in the same query ----

	@Query(QUEUE_FETCH + "order by b.createdAt, b.id")
	List<BookingEntity> findQueue();

	@Query(QUEUE_FETCH + "where b.requester.id = :userId order by b.createdAt, b.id")
	List<BookingEntity> findQueueForRequester(@Param("userId") String userId);

	@Query(QUEUE_FETCH + "where b.id = :id")
	Optional<BookingEntity> findDetailed(@Param("id") String id);
}
