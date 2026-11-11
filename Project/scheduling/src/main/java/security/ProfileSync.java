package security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import domain.Role;
import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * Keeps staff members' scheduling profiles (app_user) in step with the Core Platform, so instructors can be assigned
 * and matched by schedule import, whoever runs it. Accounts live there, not here: this only copies the name, email,
 * home department and scheduler departments. Two sources feed it: each signed-in request's token, and the Core
 * Platform's staff list ({@code integration.StaffDirectory}), which brings in people who have not signed in yet.
 * A claim a token leaves out keeps the stored value, so a token with just a name (the dev picker's) never blanks an
 * email. Students are not stored (TR1.3).
 */
@Component
public class ProfileSync {

	/** The longest name app_user.display_name holds. */
	static final int MAX_NAME_LENGTH = 150;

	private final AppUserRepository users;

	public ProfileSync(AppUserRepository users) {
		this.users = users;
	}

	/**
	 * One staff member as the Core Platform's staff list gives them, with the same meaning as the token claims.
	 *
	 * @param department  an instructor's home department
	 * @param departments the departments a scheduler manages
	 */
	public record Staff(String id, String name, String email, Role role, String department, List<String> departments) {
	}

	/** Joins the request's transaction, if any, so a test that rolls back takes the profile with it. */
	@Transactional
	public void sync(Jwt jwt) {
		Optional<Role> role = SecurityConfig.roleOf(jwt);
		String id = jwt.getSubject();
		if (id == null || role.isEmpty() || role.get() == Role.STUDENT) {
			return;
		}
		Optional<AppUserEntity> stored = users.findById(id);
		String email = jwt.hasClaim(SecurityConfig.EMAIL_CLAIM)
				? blankToNull(jwt.getClaimAsString(SecurityConfig.EMAIL_CLAIM))
				: stored.map(AppUserEntity::getEmail).orElse(null);
		String department = jwt.hasClaim(SecurityConfig.DEPARTMENT_CLAIM)
				? blankToNull(jwt.getClaimAsString(SecurityConfig.DEPARTMENT_CLAIM))
				: stored.map(AppUserEntity::getDepartment).orElse(null);
		save(stored, id, jwt.getClaimAsString(SecurityConfig.NAME_CLAIM), email, department, departmentsOf(jwt));
	}

	/** The staff list is complete for each person, so every field is copied as given. Students are skipped. */
	@Transactional
	public void sync(Collection<Staff> staff) {
		for (Staff member : staff) {
			if (member.id() == null || member.role() == null || member.role() == Role.STUDENT) {
				continue;
			}
			save(users.findById(member.id()), member.id(), member.name(), blankToNull(member.email()),
					blankToNull(member.department()), member.departments() == null ? List.of() : member.departments());
		}
	}

	private void save(Optional<AppUserEntity> stored, String id, String name, String email, String department,
			List<String> departments) {
		String shown = Optional.ofNullable(blankToNull(name)).orElse(id);
		String displayName = shown.length() > MAX_NAME_LENGTH ? shown.substring(0, MAX_NAME_LENGTH) : shown;
		if (stored.isEmpty()) {
			AppUserEntity profile = new AppUserEntity(id, displayName, email, department);
			authorize(profile, departments);
			users.save(profile);
			return;
		}
		AppUserEntity profile = stored.get();
		if (!displayName.equals(profile.getDisplayName()) || !Objects.equals(email, profile.getEmail())
				|| !Objects.equals(department, profile.getDepartment())) {
			profile.updateProfile(displayName, email, department);
		}
		authorize(profile, departments);
	}

	/** The scheduler departments a token lists; none when it has no such claim. */
	private static List<String> departmentsOf(Jwt jwt) {
		Object raw = jwt.getClaim(SecurityConfig.DEPARTMENTS_CLAIM);
		if (!(raw instanceof List<?> departments)) {
			return List.of();
		}
		return departments.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

	/**
	 * Adds the scheduler departments given. Idempotent: {@code authorizeDepartment} is a Set add, so applying the same
	 * list again on every request changes nothing. Never removes a department: this matches the "a claim the token
	 * leaves out keeps the stored value" rule for the other fields (and lets {@link config.DevSeed} seed departments a
	 * dev token does not carry).
	 */
	private static void authorize(AppUserEntity profile, List<String> departments) {
		for (String department : departments) {
			if (!department.isBlank()) {
				profile.authorizeDepartment(department.trim());
			}
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
