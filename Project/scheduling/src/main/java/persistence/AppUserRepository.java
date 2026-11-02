package persistence;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** SHARED with the other streams (see AppUserEntity). Import looks instructors up by display name. */
public interface AppUserRepository extends JpaRepository<AppUserEntity, String> {

	/** More than one person can share a name; the import service reports "ambiguous" instead of guessing. */
	List<AppUserEntity> findByDisplayNameIgnoreCase(String displayName);

	/** Empty for an unknown user or a scheduler with no rows: they may not change anything (TR3.4). */
	@Query("select d from AppUserEntity u join u.authorizedDepartments d where u.id = :id")
	Set<String> findAuthorizedDepartments(@Param("id") String id);
}
