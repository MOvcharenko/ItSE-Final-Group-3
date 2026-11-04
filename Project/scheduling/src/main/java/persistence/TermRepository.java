package persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import domain.TermStatus;
import jakarta.persistence.LockModeType;

public interface TermRepository extends JpaRepository<TermEntity, String> {

	List<TermEntity> findAllByOrderByStartDateDescNameAsc();

	List<TermEntity> findByStatusOrderByStartDateDescNameAsc(TermStatus status);

	/** Terms that share at least one day with the window [from, to]. */
	@Query("select t from TermEntity t where t.startDate <= :to and t.endDate >= :from order by t.startDate, t.name")
	List<TermEntity> findOverlapping(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/** Term ids and names only, for matching a typed name without loading any term. */
	@Query("select t.id as id, t.name as name from TermEntity t")
	List<TermName> findAllNames();

	interface TermName {
		String getId();

		String getName();
	}

	boolean existsByNameIgnoreCase(String name);

	boolean existsByNameIgnoreCaseAndIdNot(String name, String id);

	/**
	 * Row lock: call it FIRST in any transaction that changes a term or its sections, before loading the sections.
	 * Changes to one term queue up here, so each one sees the previous one's commit.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from TermEntity t where t.id = :id")
	Optional<TermEntity> lockById(@Param("id") String id);
}
