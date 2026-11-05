package persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import domain.ConflictType;
import domain.ResolutionState;
import domain.Severity;

public interface ConflictRepository extends JpaRepository<ConflictEntity, String> {

	@Query("""
			select c from ConflictEntity c
			left join fetch c.room
			left join fetch c.instructor
			left join fetch c.equipment
			left join fetch c.booking
			where c.term.id = :termId
			order by case c.severity when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
			c.detectedAt desc
			""")
	List<ConflictEntity> findForTermDetailed(@Param("termId") String termId);

	List<ConflictEntity> findByTerm_Id(String termId);

	List<ConflictEntity> findByTerm_IdAndResolutionState(String termId, ResolutionState resolutionState);

	Optional<ConflictEntity> findByTerm_IdAndFingerprint(String termId, String fingerprint);

	int countByTerm_IdAndResolutionStateAndSeverity(String termId, ResolutionState state, Severity severity);

	@Query("select count(c) from ConflictEntity c where c.term.id = :termId and c.resolutionState = 'OPEN' and c.severity = 'HIGH'")
	int countOpenHigh(@Param("termId") String termId);

	void deleteByTerm_Id(String termId);
}
