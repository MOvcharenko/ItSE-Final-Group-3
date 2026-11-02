package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatchEntity, String> {

	/** Newest first, for an "import history" list. Rows load lazily, inside the caller's transaction. */
	List<ImportBatchEntity> findAllByOrderByImportedAtDesc();
}
