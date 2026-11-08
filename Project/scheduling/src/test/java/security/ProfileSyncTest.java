package security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

import persistence.AppUserEntity;
import persistence.AppUserRepository;

class ProfileSyncTest {

	private final AppUserRepository users = mock(AppUserRepository.class);
	private final ProfileSync sync = new ProfileSync(users);

	private static Jwt.Builder token(String subject, String role) {
		return Jwt.withTokenValue("token").header("alg", "RS256").subject(subject).claim("role", role);
	}

	@Test
	@DisplayName("An instructor's first sign-in creates their profile from the token, so they can be assigned")
	void firstSignInCreatesProfile() {
		when(users.findById("T9")).thenReturn(Optional.empty());

		sync.sync(token("T9", "INSTRUCTOR").claim("name", "Pat New").claim("email", "pat@univ.edu")
				.claim("department", "PHYS").build());

		ArgumentCaptor<AppUserEntity> saved = ArgumentCaptor.forClass(AppUserEntity.class);
		verify(users).save(saved.capture());
		assertThat(saved.getValue().getId()).isEqualTo("T9");
		assertThat(saved.getValue().getDisplayName()).isEqualTo("Pat New");
		assertThat(saved.getValue().getEmail()).isEqualTo("pat@univ.edu");
		assertThat(saved.getValue().getDepartment()).isEqualTo("PHYS");
	}

	@Test
	@DisplayName("A changed name or department in the Core Platform reaches the profile on the next request")
	void changesAreCopied() {
		AppUserEntity profile = new AppUserEntity("U02", "L. Bianchi", "l.bianchi@univ.edu", "MATH");
		when(users.findById("U02")).thenReturn(Optional.of(profile));

		sync.sync(token("U02", "INSTRUCTOR").claim("name", "Laura Bianchi").claim("department", "PHYS").build());

		assertThat(profile.getDisplayName()).isEqualTo("Laura Bianchi");
		assertThat(profile.getDepartment()).isEqualTo("PHYS");
		assertThat(profile.getEmail()).as("no email claim keeps the stored email").isEqualTo("l.bianchi@univ.edu");
		verify(users, never()).save(any());
	}

	@Test
	@DisplayName("A token with only a name (the dev picker's) leaves the email and home department alone")
	void nameOnlyTokenKeepsTheRest() {
		AppUserEntity profile = new AppUserEntity("U02", "L. Bianchi", "l.bianchi@univ.edu", "MATH");
		when(users.findById("U02")).thenReturn(Optional.of(profile));

		sync.sync(token("U02", "INSTRUCTOR").claim("name", "L. Bianchi").build());

		assertThat(profile.getEmail()).isEqualTo("l.bianchi@univ.edu");
		assertThat(profile.getDepartment()).isEqualTo("MATH");
	}

	@Test
	@DisplayName("Students get no profile: the SIS owns them")
	void studentsAreNotStored() {
		sync.sync(token("U03", "STUDENT").claim("name", "Jordan Ellis").build());

		verifyNoInteractions(users);
	}

	@Test
	@DisplayName("A name longer than the profile holds is shortened rather than refused")
	void longNameIsShortened() {
		when(users.findById("T8")).thenReturn(Optional.empty());

		sync.sync(token("T8", "SCHEDULER").claim("name", "N".repeat(200)).build());

		ArgumentCaptor<AppUserEntity> saved = ArgumentCaptor.forClass(AppUserEntity.class);
		verify(users).save(saved.capture());
		assertThat(saved.getValue().getDisplayName()).hasSize(ProfileSync.MAX_NAME_LENGTH);
	}

}
