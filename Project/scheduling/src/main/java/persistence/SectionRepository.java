package persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SectionRepository extends JpaRepository<SectionEntity, String> {

	@Query("""
			select s from SectionEntity s join fetch s.subject left join fetch s.room
			where s.term.id = :termId order by s.subject.courseCode, s.sectionNumber""")
	List<SectionEntity> findForTerm(@Param("termId") String termId);

	boolean existsByTerm_IdAndSubject_IdAndSectionNumber(String termId, String subjectId, String sectionNumber);

	/** Can contain null: an imported subject may have no department yet. */
	@Query("select distinct s.subject.department from SectionEntity s where s.term.id = :termId")
	List<String> findDepartments(@Param("termId") String termId);

	@Query("select s.id from SectionEntity s")
	Set<String> findAllIds();

	/** Finds which term to lock without loading the section first. */
	@Query("select s.term.id from SectionEntity s where s.id = :id")
	Optional<String> findTermId(@Param("id") String id);
}
