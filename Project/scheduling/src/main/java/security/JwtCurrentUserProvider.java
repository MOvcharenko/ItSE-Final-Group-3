package security;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import domain.Role;
import persistence.AppUserRepository;

/**
 * Builds the {@link CurrentUser} for the current request: id, name and role from the JWT (NFR1.4),
 * a scheduler's departments from the scheduler_department table (TR3.4).
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

	private final AppUserRepository users;

	public JwtCurrentUserProvider(AppUserRepository users) {
		this.users = users;
	}

	@Override
	public CurrentUser get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
			throw new AuthenticationCredentialsNotFoundException("No signed-in user.");
		}
		String id = jwt.getSubject();
		if (id == null) {
			throw new AccessDeniedException("The sign-in token does not name a user.");
		}
		Role role = SecurityConfig.roleOf(jwt)
				.orElseThrow(() -> new AccessDeniedException("The sign-in token does not carry a known role."));
		String name = jwt.getClaimAsString(SecurityConfig.NAME_CLAIM);
		Set<String> departments = role == Role.SCHEDULER ? users.findAuthorizedDepartments(id) : Set.of();
		return new CurrentUser(id, name == null ? id : name, role, departments);
	}

}
