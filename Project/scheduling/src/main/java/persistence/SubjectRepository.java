package persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SubjectRepository extends JpaRepository<SubjectEntity, String> {

	List<SubjectEntity> findAllByOrderByCourseCodeAsc();

	Optional<SubjectEntity> findByCourseCodeIgnoreCase(String courseCode);

	boolean existsByCourseCodeIgnoreCase(String courseCode);

	/**
	 * Ids of subjects that have at least one PLACED section in any term. Everything else is "Not yet scheduled"
	 * (FR3.1, FR3.4). Native on purpose: the section table belongs to the timetable stream, and this only needs
	 * two of its columns.
	 */
	@Query(value = "select distinct subject_id from section where room_id is not null", nativeQuery = true)
	Set<String> scheduledSubjectIds();
}
