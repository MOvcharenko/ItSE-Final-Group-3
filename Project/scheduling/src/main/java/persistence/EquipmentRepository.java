package persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import domain.ResourceStatus;
import jakarta.persistence.LockModeType;

public interface EquipmentRepository extends JpaRepository<EquipmentEntity, String> {

	List<EquipmentEntity> findAllByOrderByNameAsc();

	List<EquipmentEntity> findByStatusOrderByNameAsc(ResourceStatus status);

	Optional<EquipmentEntity> findByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCase(String name);

	/** Same role as RoomRepository.lockById, for an equipment booking (NFR3.1). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from EquipmentEntity e where e.id = :id")
	Optional<EquipmentEntity> lockById(@Param("id") String id);
}
