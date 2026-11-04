package persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import domain.ResourceStatus;
import jakarta.persistence.LockModeType;

public interface RoomRepository extends JpaRepository<RoomEntity, String> {

	List<RoomEntity> findAllByOrderByNameAsc();

	/** Search candidates: Maintenance and Checked out are hidden (FR2.5). */
	List<RoomEntity> findByStatusOrderByNameAsc(ResourceStatus status);

	Optional<RoomEntity> findByNameIgnoreCase(String name);

	/**
	 * Row lock for atomic approval (NFR3.1): call it FIRST inside the approving transaction, then count overlaps.
	 * Two approvals for the same room queue up here, so the second one sees the first one's commit.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from RoomEntity r where r.id = :id")
	Optional<RoomEntity> lockById(@Param("id") String id);
}
