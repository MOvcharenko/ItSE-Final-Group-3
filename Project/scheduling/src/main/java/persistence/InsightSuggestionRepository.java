package persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InsightSuggestionRepository extends JpaRepository<InsightSuggestionEntity, String> {

	@Query("""
			select i from InsightSuggestionEntity i
			left join fetch i.instructor
			left join fetch i.room
			left join fetch i.relatedConflict
			where i.term.id = :termId
			order by i.createdAt asc
			""")
	List<InsightSuggestionEntity> findByTerm_Id(@Param("termId") String termId);

	@Query("""
			select i from InsightSuggestionEntity i
			left join fetch i.instructor
			left join fetch i.room
			left join fetch i.relatedConflict
			where i.term.id = :termId and i.dismissed = false
			order by i.createdAt asc
			""")
	List<InsightSuggestionEntity> findActiveByTerm_Id(@Param("termId") String termId);

	@Query("""
			select i from InsightSuggestionEntity i
			where i.term.id = :termId and i.axis = 'INSTRUCTOR_UTILIZATION' and i.instructor.id = :instructorId
			""")
	Optional<InsightSuggestionEntity> findInstructorSuggestion(@Param("termId") String termId, @Param("instructorId") String instructorId);

	@Query("""
			select i from InsightSuggestionEntity i
			where i.term.id = :termId and i.axis = 'ROOM_CAPACITY' and i.room.id = :roomId and i.measure = :measure
			""")
	Optional<InsightSuggestionEntity> findRoomSuggestion(@Param("termId") String termId, @Param("roomId") String roomId, @Param("measure") domain.InsightMeasure measure);
}
