package signin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The local sign-in server (profile {@code local}).
 *
 * @param redirectUris           where the browser may return after sign-in: the UI's signin-callback.html at each
 *                               address the app is opened at
 * @param postLogoutRedirectUris where the browser may return after "Switch account"
 * @param accessTokenTtl         how long a sign-in lasts; the UI has no refresh, so it then asks to sign in again
 * @param signingKeyFile         where the token-signing key is kept, so sign-ins survive a restart
 * @param seedAccounts           accounts created when there are none yet (first start only)
 */
@Validated
@ConfigurationProperties("scheduling.signin")
public record SignInProperties(
		@NotEmpty List<String> redirectUris,
		@NotEmpty List<String> postLogoutRedirectUris,
		@NotNull Duration accessTokenTtl,
		@NotNull Path signingKeyFile,
		List<@Valid SeedAccount> seedAccounts) {

	public SignInProperties {
		redirectUris = List.copyOf(redirectUris);
		postLogoutRedirectUris = List.copyOf(postLogoutRedirectUris);
		seedAccounts = seedAccounts == null ? List.of() : List.copyOf(seedAccounts);
	}

	/** A first-start account. The password must already be encoded. */
	public record SeedAccount(
			@NotBlank String id,
			@NotBlank String username,
			@NotBlank @Pattern(regexp = "\\{[a-z0-9]+\\}.+", message = "must be an encoded password such as {bcrypt}...") String password,
			@NotBlank String name,
			String email,
			@NotNull Role role,
			boolean admin,
			List<String> departments,
			String homeDepartment) {

		/** Departments only for a scheduler and a home department only for an instructor, as on the admin page. */
		Account toAccount() {
			return new Account(id, username, password, name, email, role, admin, true,
					role == Role.SCHEDULER ? departments : List.of(), role == Role.INSTRUCTOR ? homeDepartment : null);
		}

	}

}
