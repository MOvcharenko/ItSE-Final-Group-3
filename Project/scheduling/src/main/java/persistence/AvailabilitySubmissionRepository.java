package persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilitySubmissionRepository extends JpaRepository<AvailabilitySubmissionEntity, AvailabilitySubmissionEntity.Pk> {

	@Query("""
			select s from AvailabilitySubmissionEntity s
			where s.term.id = :termId and s.instructor.id = :instructorId
			""")
	Optional<AvailabilitySubmissionEntity> findByTermAndInstructor(@Param("termId") String termId,
			@Param("instructorId") String instructorId);

	void deleteByTerm_IdAndInstructor_Id(String termId, String instructorId);
}
