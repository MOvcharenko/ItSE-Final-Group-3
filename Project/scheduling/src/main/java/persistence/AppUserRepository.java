package persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/** SHARED with the other streams (see AppUserEntity). Import looks instructors up by display name. */
public interface AppUserRepository extends JpaRepository<AppUserEntity, String> {

	/** More than one person can share a name; the import service reports "ambiguous" instead of guessing. */
	List<AppUserEntity> findByDisplayNameIgnoreCase(String displayName);
}
