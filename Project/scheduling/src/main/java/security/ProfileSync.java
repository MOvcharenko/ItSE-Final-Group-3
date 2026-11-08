package security;

import java.util.Objects;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import domain.Role;
import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * Keeps a staff member's scheduling profile (app_user) in step with their token, so an instructor can be assigned and
 * matched by schedule import once they have signed in, whoever runs the Core Platform. Accounts live there, not here:
 * this only copies the name, email and home department the token carries. A claim the token leaves out keeps the
 * stored value, so a token with just a name (the dev picker's) never blanks an email. Students are not stored (TR1.3).
 */
@Component
public class ProfileSync {

	/** The longest name app_user.display_name holds. */
	static final int MAX_NAME_LENGTH = 150;

	private final AppUserRepository users;

	public ProfileSync(AppUserRepository users) {
		this.users = users;
	}

	/** Joins the request's transaction, if any, so a test that rolls back takes the profile with it. */
	@Transactional
	public void sync(Jwt jwt) {
		Optional<Role> role = SecurityConfig.roleOf(jwt);
		String id = jwt.getSubject();
		if (id == null || role.isEmpty() || role.get() == Role.STUDENT) {
			return;
		}
		String name = Optional.ofNullable(blankToNull(jwt.getClaimAsString(SecurityConfig.NAME_CLAIM))).orElse(id);
		String displayName = name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
		Optional<AppUserEntity> stored = users.findById(id);
		String email = jwt.hasClaim(SecurityConfig.EMAIL_CLAIM)
				? blankToNull(jwt.getClaimAsString(SecurityConfig.EMAIL_CLAIM))
				: stored.map(AppUserEntity::getEmail).orElse(null);
		String department = jwt.hasClaim(SecurityConfig.DEPARTMENT_CLAIM)
				? blankToNull(jwt.getClaimAsString(SecurityConfig.DEPARTMENT_CLAIM))
				: stored.map(AppUserEntity::getDepartment).orElse(null);

		if (stored.isEmpty()) {
			users.save(new AppUserEntity(id, displayName, email, department));
			return;
		}
		AppUserEntity profile = stored.get();
		if (!displayName.equals(profile.getDisplayName()) || !Objects.equals(email, profile.getEmail())
				|| !Objects.equals(department, profile.getDepartment())) {
			profile.updateProfile(displayName, email, department);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
