package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntryEntity, String> {

	List<AuditLogEntryEntity> findByConflictId(String conflictId);

	/** Dismissals of the term's conflicts, newest first, with who made them in the same query. */
	@Query("""
			select a from AuditLogEntryEntity a join fetch a.user
			where a.conflictId in (select c.id from ConflictEntity c where c.term.id = :termId)
			order by a.occurredAt desc
			""")
	List<AuditLogEntryEntity> findForTerm(@Param("termId") String termId);

	List<AuditLogEntryEntity> findByUser_IdOrderByOccurredAtDesc(String userId);
}
