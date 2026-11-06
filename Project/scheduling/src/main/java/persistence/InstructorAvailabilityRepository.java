package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstructorAvailabilityRepository extends JpaRepository<InstructorAvailabilityEntity, String> {

	@Query("""
			select a from InstructorAvailabilityEntity a
			join fetch a.instructor
			where a.term.id = :termId
			""")
	List<InstructorAvailabilityEntity> findByTerm_Id(@Param("termId") String termId);

	@Query("""
			select a from InstructorAvailabilityEntity a
			where a.term.id = :termId and a.instructor.id = :instructorId
			order by a.dayOfWeek, a.startTime
			""")
	List<InstructorAvailabilityEntity> findByTermAndInstructor(@Param("termId") String termId,
			@Param("instructorId") String instructorId);

	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from InstructorAvailabilityEntity a where a.term.id = :termId and a.instructor.id = :instructorId")
	void deleteByTerm_IdAndInstructor_Id(@Param("termId") String termId, @Param("instructorId") String instructorId);
}
