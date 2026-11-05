package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntryEntity, String> {

	List<AuditLogEntryEntity> findByConflictId(String conflictId);

	List<AuditLogEntryEntity> findByUser_IdOrderByOccurredAtDesc(String userId);
}
