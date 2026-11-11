package accounts;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The sign-in server's settings ({@code accounts.*}).
 *
 * @param issuer         this server's address, the {@code iss} of its tokens; the scheduling module's
 *                       {@code issuer-uri} must be the same
 * @param appUrls        the addresses the Academic Scheduling UI is opened at, e.g. {@code http://localhost:8080}. Sign-in
 *                       returns to {@code <app>/signin-callback.html}, sign-out to {@code <app>/}, and the UI's
 *                       cross-origin token request is allowed from each. The first is the "Back to the app" link
 * @param clientId       how the scheduling UI is registered; its {@code scheduling.auth.client-id}
 * @param scope          the scopes the UI asks for; its {@code scheduling.auth.scope}
 * @param apiAudience    the {@code aud} of access tokens; the scheduling module accepts only this
 * @param accessTokenTtl how long a sign-in lasts. A disabled account keeps a token it already has until then
 * @param signingKeyFile where the token-signing key is kept, so sign-ins survive a restart
 * @param directory      how the scheduling server signs in to read the staff list ({@code GET /api/staff})
 * @param seedAccounts   accounts created when there are none yet (first start only)
 */
@Validated
@ConfigurationProperties("accounts")
public record AccountsProperties(
		@NotBlank String issuer,
		@NotEmpty List<@NotBlank String> appUrls,
		@NotBlank String clientId,
		@NotBlank String scope,
		@NotBlank String apiAudience,
		@NotNull Duration accessTokenTtl,
		@NotNull Path signingKeyFile,
		@NotNull @Valid Directory directory,
		List<@Valid SeedAccount> seedAccounts) {

	public AccountsProperties {
		appUrls = appUrls.stream().map(AccountsProperties::withoutTrailingSlash).toList();
		seedAccounts = seedAccounts == null ? List.of() : List.copyOf(seedAccounts);
	}

	public List<String> redirectUris() {
		return appUrls.stream().map(url -> url + "/signin-callback.html").toList();
	}

	public List<String> postLogoutRedirectUris() {
		return appUrls.stream().map(url -> url + "/").toList();
	}

	/** Where "Back to the app" and a sign-out that cannot name its return address go. */
	public String appHome() {
		return appUrls.get(0) + "/";
	}

	private static String withoutTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/**
	 * The scheduling server's own client, for the client-credentials grant. It has no user: it only reads the staff
	 * list, so an account created here can be assigned to classes before that person has ever signed in.
	 *
	 * @param clientId     the scheduling module's {@code scheduling.directory.client-id}
	 * @param clientSecret encoded like a password, e.g. {@code {bcrypt}...}; the scheduling module holds the plain one
	 */
	public record Directory(
			@NotBlank String clientId,
			@NotBlank @Pattern(regexp = "\\{[a-z0-9]+\\}.+", message = "must be encoded, such as {bcrypt}... or {noop}...") String clientSecret) {
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
