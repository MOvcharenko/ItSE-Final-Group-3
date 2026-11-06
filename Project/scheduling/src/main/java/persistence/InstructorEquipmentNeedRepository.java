package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InstructorEquipmentNeedRepository extends JpaRepository<InstructorEquipmentNeedEntity, InstructorEquipmentNeedEntity.Pk> {

	@Query("""
			select n from InstructorEquipmentNeedEntity n
			where n.term.id = :termId and n.instructor.id = :instructorId
			order by n.itemType
			""")
	List<InstructorEquipmentNeedEntity> findByTermAndInstructor(@Param("termId") String termId,
			@Param("instructorId") String instructorId);

	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from InstructorEquipmentNeedEntity n where n.term.id = :termId and n.instructor.id = :instructorId")
	void deleteByTerm_IdAndInstructor_Id(@Param("termId") String termId, @Param("instructorId") String instructorId);
}
