package security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import domain.Role;
import persistence.AppUserRepository;

class JwtCurrentUserProviderTest {

	private final AppUserRepository users = mock(AppUserRepository.class);
	private final JwtCurrentUserProvider provider = new JwtCurrentUserProvider(users);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static void signIn(String subject, String name, String role) {
		Jwt.Builder jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).claim("name", name);
		if (role != null) {
			jwt.claim("role", role);
		}
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
	}

	@Test
	@DisplayName("TC-TR3.4: a scheduler gets id, name and role from the token, departments from the database")
	void scheduler() {
		signIn("U01", "Maya Brooks", "SCHEDULER");
		when(users.findAuthorizedDepartments("U01")).thenReturn(Set.of("CS", "MATH"));

		CurrentUser user = provider.get();

		assertThat(user).isEqualTo(new CurrentUser("U01", "Maya Brooks", Role.SCHEDULER, Set.of("CS", "MATH")));
		assertThat(user.canManage("CS")).isTrue();
		assertThat(user.canManage("ENG")).isFalse();
	}

	@Test
	@DisplayName("A scheduler with no department rows may not manage anything")
	void schedulerWithoutDepartments() {
		signIn("U09", "New Scheduler", "SCHEDULER");
		when(users.findAuthorizedDepartments("U09")).thenReturn(Set.of());

		assertThat(provider.get().canManage("CS")).isFalse();
	}

	@Test
	@DisplayName("A student needs no database lookup: students are not stored here")
	void student() {
		signIn("U03", "Jordan Ellis", "STUDENT");

		assertThat(provider.get()).isEqualTo(new CurrentUser("U03", "Jordan Ellis", Role.STUDENT, Set.of()));
		verifyNoInteractions(users);
	}

	@Test
	@DisplayName("A token without a known role is refused")
	void unknownRole() {
		signIn("U04", "Someone", "ADMIN");
		assertThatThrownBy(provider::get).isInstanceOf(AccessDeniedException.class);

		signIn("U04", "Someone", null);
		assertThatThrownBy(provider::get).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("A token that names no user is refused")
	void noSubject() {
		Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").claim("role", "INSTRUCTOR").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

		assertThatThrownBy(provider::get).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("No signed-in user is an authentication failure, which the API answers with 401")
	void notSignedIn() {
		assertThatThrownBy(provider::get).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
	}

}
