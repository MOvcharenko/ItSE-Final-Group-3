package security;

import java.util.List;
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
 * Builds the {@link CurrentUser} for the current request: id, name and role from the JWT (NFR1.4). A scheduler's
 * departments (TR3.4) come from the token's {@code departments} claim, so whoever runs the Core Platform decides them;
 * a token without that claim (the dev picker's) uses the scheduler_department table instead.
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
		Set<String> departments = role == Role.SCHEDULER ? departmentsOf(jwt, id) : Set.of();
		return new CurrentUser(id, name == null ? id : name, role, departments);
	}

	private Set<String> departmentsOf(Jwt jwt, String id) {
		List<String> claimed = jwt.getClaimAsStringList(SecurityConfig.DEPARTMENTS_CLAIM);
		return claimed == null ? users.findAuthorizedDepartments(id) : Set.copyOf(claimed);
	}

}
