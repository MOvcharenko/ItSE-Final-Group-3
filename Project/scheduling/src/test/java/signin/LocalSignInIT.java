package signin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import app.SchedulingApplication;
import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * The built-in sign-in (profile local), as the browser runs it: the sign-in form, authorization code with PKCE, the
 * token exchange, then the API with that token; and the account pages. Tests that change an account make their own,
 * so they do not depend on each other.
 */
@SpringBootTest(classes = SchedulingApplication.class, properties = {
		"spring.datasource.url=jdbc:h2:mem:local-signin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
		"scheduling.signin.signing-key-file=${java.io.tmpdir}/scheduling-signin-test-${random.uuid}/signing-key.json" })
@AutoConfigureMockMvc
@ActiveProfiles("local")
class LocalSignInIT {

	private static final String CLIENT = "scheduling-web"; // scheduling.auth.client-id's default
	private static final String REDIRECT = "http://localhost:8080/signin-callback.html";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccountService service;

	@Autowired
	AccountStore accounts;

	@Autowired
	AppUserRepository users;

	// --- sign-in and tokens -----------------------------------------------

	@Test
	@DisplayName("The browser is told to sign in here, with the logout and account pages")
	void authConfig() throws Exception {
		mvc.perform(get("/auth/config"))
				.andExpect(jsonPath("$.mode").value("oidc"))
				.andExpect(jsonPath("$.issuer").value(""))
				.andExpect(jsonPath("$.clientId").value(CLIENT))
				.andExpect(jsonPath("$.scope").value("openid profile email"))
				.andExpect(jsonPath("$.logoutUri").value("/connect/logout"))
				.andExpect(jsonPath("$.accountUri").value("/account"));
	}

	@Test
	@DisplayName("The sign-in form opens without a token; authorizing without signing in leads to it")
	void signInForm() throws Exception {
		mvc.perform(get("/login")).andExpect(status().isOk());
		mvc.perform(get(authorizeUrl("st", challenge(verifier()))).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@DisplayName("A wrong password is refused")
	void wrongPassword() throws Exception {
		assertSignInFails("m.brooks", "wrong");
	}

	@Test
	@DisplayName("Signing in gives an access token for the API with sub, name, role and audience")
	void accessToken() throws Exception {
		String verifier = verifier();
		String body = exchange(authorize(signIn("m.brooks", "Scheduler#2026"), challenge(verifier)), verifier);
		String token = JsonPath.read(body, "$.access_token");

		JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
		assertThat(claims.getSubject()).isEqualTo("U01");
		assertThat(claims.getAudience()).containsExactly("scheduling-api");
		assertThat(claims.getStringClaim("name")).isEqualTo("Maya Brooks");
		assertThat(claims.getStringClaim("role")).isEqualTo("SCHEDULER");
		assertThat(claims.getClaims()).doesNotContainKey("departments");

		mvc.perform(get("/api/terms").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("The ID token is addressed to the client, so the API refuses it")
	void idTokenRefusedByApi() throws Exception {
		String verifier = verifier();
		String body = exchange(authorize(signIn("l.bianchi", "Instructor#2026"), challenge(verifier)), verifier);
		String idToken = JsonPath.read(body, "$.id_token");

		assertThat(SignedJWT.parse(idToken).getJWTClaimsSet().getAudience()).containsExactly(CLIENT);
		mvc.perform(get("/api/terms").header("Authorization", "Bearer " + idToken)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("A wrong PKCE verifier is refused")
	void wrongVerifier() throws Exception {
		String code = authorize(signIn("j.ellis", "Student#2026"), challenge(verifier()));
		mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("grant_type", "authorization_code").param("code", code).param("redirect_uri", REDIRECT)
						.param("client_id", CLIENT).param("code_verifier", verifier()))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Logout with the ID token ends the session and returns to the app; the next sign-in asks again")
	void logout() throws Exception {
		MockHttpSession session = signIn("m.brooks", "Scheduler#2026");
		String verifier = verifier();
		String idToken = JsonPath.read(exchange(authorize(session, challenge(verifier)), verifier), "$.id_token");

		URI logout = UriComponentsBuilder.fromPath("/connect/logout")
				.queryParam("id_token_hint", idToken)
				.queryParam("post_logout_redirect_uri", "http://localhost:8080/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(redirectedUrl("http://localhost:8080/"));

		mvc.perform(get(authorizeUrl("s", challenge(verifier()))).session(session).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@DisplayName("Logout with an ID token it does not know (as after a restart) still signs out and returns to the app")
	void logoutWithUnknownIdToken() throws Exception {
		MockHttpSession session = signIn("m.brooks", "Scheduler#2026");
		URI logout = UriComponentsBuilder.fromPath("/connect/logout")
				.queryParam("id_token_hint", "issued-before-a-restart")
				.queryParam("post_logout_redirect_uri", "http://localhost:8080/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(redirectedUrl("/"));
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	@DisplayName("Switch account without an ID token (logout names the client) still signs out, so the next sign-in asks again")
	void logoutWithoutIdToken() throws Exception {
		MockHttpSession session = signIn("m.brooks", "Scheduler#2026");
		URI logout = UriComponentsBuilder.fromPath("/connect/logout")
				.queryParam("client_id", CLIENT)
				.queryParam("post_logout_redirect_uri", "http://localhost:8080/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(status().is3xxRedirection());
		assertThat(session.isInvalid()).isTrue();
		mvc.perform(get(authorizeUrl("st", challenge(verifier()))).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
	}

	// --- accounts -----------------------------------------------------------

	@Test
	@DisplayName("First start creates the demo accounts, with hashed passwords, staff profiles and Maya's departments")
	void seeded() {
		assertThat(accounts.all()).extracting(Account::id).contains("U01", "U02", "U03");
		assertThat(accounts.byId("U01").orElseThrow().password()).startsWith("{bcrypt}");
		assertThat(accounts.byId("U01").orElseThrow().admin()).isTrue();
		assertThat(users.findAuthorizedDepartments("U01")).containsExactlyInAnyOrder("CS", "MATH");
		assertThat(users.findById("U02")).map(AppUserEntity::getDisplayName).contains("L. Bianchi");
		// the same home department as the dev and test profiles give her
		assertThat(users.findById("U02")).map(AppUserEntity::getDepartment).contains("MATH");
		assertThat(accounts.byId("U02").orElseThrow().homeDepartment()).isEqualTo("MATH");
		assertThat(users.findById("U01")).map(AppUserEntity::getDepartment).isEmpty();
		// students are not stored, so Jordan Ellis is never offered as an instructor
		assertThat(users.findById("U03")).isEmpty();
	}

	@Test
	@DisplayName("A student created by an administrator signs in, gets no scheduling profile, and is not listed as an instructor")
	void studentHasNoProfile() throws Exception {
		String username = newAccount("STUDENT", false, "first-password");
		String id = accounts.byUsername(username).orElseThrow().id();
		assertThat(users.findById(id)).isEmpty();

		String verifier = verifier();
		String token = JsonPath.read(exchange(authorize(signIn("m.brooks", "Scheduler#2026"), challenge(verifier)), verifier),
				"$.access_token");
		String instructors = mvc.perform(get("/api/instructors").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(instructors).doesNotContain(id).doesNotContain("Jordan Ellis");
	}

	@Test
	@DisplayName("A user changes their password; the old one stops working and the new one works")
	void changePassword() throws Exception {
		String username = newAccount("INSTRUCTOR", false, "first-password");
		mvc.perform(post("/account/password").session(signIn(username, "first-password")).with(csrf())
						.param("current", "first-password").param("password", "second-password").param("confirmation", "second-password"))
				.andExpect(flash().attribute("message", "Your password has been changed."));

		assertSignInFails(username, "first-password");
		signIn(username, "second-password");
	}

	@Test
	@DisplayName("A password change needs the current password, a long enough new one, and a matching confirmation")
	void passwordRules() throws Exception {
		String username = newAccount("STUDENT", false, "first-password");
		MockHttpSession session = signIn(username, "first-password");
		change(session, "wrong", "new-password-1", "new-password-1", "Your current password is not correct.");
		change(session, "first-password", "short", "short", "The new password must be at least 10 characters.");
		change(session, "first-password", "new-password-1", "new-password-2", "The two new passwords do not match.");
		signIn(username, "first-password");
	}

	@Test
	@DisplayName("Only an administrator can manage accounts")
	void adminOnly() throws Exception {
		mvc.perform(get("/admin/accounts").session(signIn("l.bianchi", "Instructor#2026"))).andExpect(status().isForbidden());
		mvc.perform(get("/admin/accounts").session(signIn("m.brooks", "Scheduler#2026"))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("An administrator creates a scheduler, who can then sign in and manage their departments")
	void createAccount() throws Exception {
		String id = "T" + UUID.randomUUID().toString().substring(0, 8);
		mvc.perform(post("/admin/accounts").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
						.param("id", id).param("username", id.toLowerCase()).param("name", "Ada New")
						.param("role", "SCHEDULER").param("departments", "cs, phys")
						.param("password", "first-password").param("confirmation", "first-password"))
				.andExpect(flash().attribute("message", "Account " + id + " created."));

		Account created = accounts.byId(id).orElseThrow();
		assertThat(created.departments()).containsExactlyInAnyOrder("CS", "PHYS");
		assertThat(created.password()).startsWith("{bcrypt}").doesNotContain("first-password");
		// the list the API checks, not a copy of it
		assertThat(users.findAuthorizedDepartments(id)).containsExactlyInAnyOrder("CS", "PHYS");
		signIn(id.toLowerCase(), "first-password");
	}

	@Test
	@DisplayName("An instructor's home department reaches their scheduling profile; a scheduler's is ignored")
	void homeDepartment() throws Exception {
		MockHttpSession admin = signIn("m.brooks", "Scheduler#2026");
		String instructor = "T" + UUID.randomUUID().toString().substring(0, 8);
		String scheduler = "T" + UUID.randomUUID().toString().substring(0, 8);
		for (String[] account : new String[][] { { instructor, "INSTRUCTOR" }, { scheduler, "SCHEDULER" } }) {
			mvc.perform(post("/admin/accounts").session(admin).with(csrf())
							.param("id", account[0]).param("username", account[0].toLowerCase()).param("name", "Pat " + account[0])
							.param("role", account[1]).param("homeDepartment", " phys ")
							.param("password", "first-password").param("confirmation", "first-password"))
					.andExpect(flash().attribute("message", "Account " + account[0] + " created."));
		}

		assertThat(users.findById(instructor)).map(AppUserEntity::getDepartment).contains("PHYS");
		assertThat(accounts.byId(instructor).orElseThrow().homeDepartment()).isEqualTo("PHYS");
		assertThat(users.findById(scheduler)).map(AppUserEntity::getDepartment).isEmpty();
	}

	@Test
	@DisplayName("A name longer than the scheduling profile holds is refused")
	void nameTooLong() throws Exception {
		mvc.perform(post("/admin/accounts").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
						.param("id", "X3").param("username", "x3").param("name", "N".repeat(151)).param("role", "STUDENT")
						.param("password", "long-enough-1").param("confirmation", "long-enough-1"))
				.andExpect(flash().attribute("error", "The name may be at most 150 characters."));
	}

	@Test
	@DisplayName("Every account needs a role, and duplicate ids and usernames are refused")
	void createRules() throws Exception {
		MockHttpSession admin = signIn("m.brooks", "Scheduler#2026");
		create(admin, "X1", "x1", "", "Choose a role of Student, Instructor, Scheduler.");
		create(admin, "X1", "x1", "ADMIN", "Choose a role of Student, Instructor, Scheduler.");
		create(admin, "U01", "someone", "STUDENT", "Another account already has the id U01.");
		create(admin, "X2", "M.Brooks", "STUDENT", "Another account already has the username M.Brooks.");
	}

	@Test
	@DisplayName("A disabled account cannot sign in until enabled again")
	void disable() throws Exception {
		String username = newAccount("STUDENT", false, "first-password");
		String id = accounts.byUsername(username).orElseThrow().id();
		MockHttpSession admin = signIn("m.brooks", "Scheduler#2026");

		mvc.perform(post("/admin/accounts/" + id + "/enabled").session(admin).with(csrf()).param("enabled", "false"))
				.andExpect(flash().attribute("message", "Disabled " + id + "."));
		assertSignInFails(username, "first-password");

		mvc.perform(post("/admin/accounts/" + id + "/enabled").session(admin).with(csrf()).param("enabled", "true"));
		signIn(username, "first-password");
	}

	@Test
	@DisplayName("Disabling a signed-in account stops it at once: its token, its sign-in session and its account pages")
	void disableWhileSignedIn() throws Exception {
		String username = newAccount("INSTRUCTOR", true, "first-password");
		String id = accounts.byUsername(username).orElseThrow().id();
		MockHttpSession session = signIn(username, "first-password");
		MockHttpSession adminPages = signIn(username, "first-password");
		String verifier = verifier();
		String token = JsonPath.read(exchange(authorize(session, challenge(verifier)), verifier), "$.access_token");
		mvc.perform(get("/api/terms").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
		mvc.perform(get("/admin/accounts").session(adminPages)).andExpect(status().isOk());

		mvc.perform(post("/admin/accounts/" + id + "/enabled").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
				.param("enabled", "false"));

		mvc.perform(get("/api/terms").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
		// the open session no longer hands out codes, and the disabled administrator loses the admin pages
		mvc.perform(get(authorizeUrl("st", challenge(verifier()))).session(session).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
		mvc.perform(get("/admin/accounts").session(adminPages)).andExpect(redirectedUrl("/login"));
	}

	@Test
	@DisplayName("An administrator cannot disable their own account")
	void cannotDisableSelf() throws Exception {
		mvc.perform(post("/admin/accounts/U01/enabled").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
						.param("enabled", "false"))
				.andExpect(flash().attribute("error", "You cannot disable your own account."));
	}

	@Test
	@DisplayName("An administrator resets a password")
	void resetPassword() throws Exception {
		String username = newAccount("STUDENT", false, "first-password");
		String id = accounts.byUsername(username).orElseThrow().id();
		mvc.perform(post("/admin/accounts/" + id + "/password").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
						.param("password", "reset-password").param("confirmation", "reset-password"))
				.andExpect(flash().attribute("message", "Password for " + id + " reset."));
		signIn(username, "reset-password");
	}

	@Test
	@DisplayName("The signing key is written on first start and the same key is read back afterwards")
	void signingKeyPersists() throws Exception {
		Path file = Files.createTempDirectory("signin-key").resolve("nested/signing-key.json");
		RSAKey created = SignInSecurityConfig.loadOrCreateKey(file);
		RSAKey loaded = SignInSecurityConfig.loadOrCreateKey(file);

		assertThat(loaded.getKeyID()).isEqualTo(created.getKeyID());
		assertThat(loaded.toRSAPublicKey()).isEqualTo(created.toRSAPublicKey());
		assertThat(loaded.isPrivate()).isTrue();
	}

	// --- helpers -----------------------------------------------------------

	/** A fresh account of its own for a test that changes it; returns its username. */
	private String newAccount(String role, boolean admin, String password) {
		String id = "T" + UUID.randomUUID().toString().substring(0, 8);
		service.create(id, id.toLowerCase(), "Test " + id, null, role, admin, null, null, password, password);
		return id.toLowerCase();
	}

	private MockHttpSession signIn(String username, String password) throws Exception {
		MockHttpSession session = new MockHttpSession();
		mvc.perform(post("/login").session(session).with(csrf()).param("username", username).param("password", password))
				.andExpect(redirectedUrl("/"));
		return session;
	}

	private void assertSignInFails(String username, String password) throws Exception {
		mvc.perform(post("/login").with(csrf()).param("username", username).param("password", password))
				.andExpect(redirectedUrl("/login?error"));
	}

	private void change(MockHttpSession session, String current, String password, String confirmation, String error)
			throws Exception {
		mvc.perform(post("/account/password").session(session).with(csrf())
						.param("current", current).param("password", password).param("confirmation", confirmation))
				.andExpect(flash().attribute("error", error));
	}

	private void create(MockHttpSession admin, String id, String username, String role, String error) throws Exception {
		mvc.perform(post("/admin/accounts").session(admin).with(csrf())
						.param("id", id).param("username", username).param("name", "X").param("role", role)
						.param("password", "long-enough-1").param("confirmation", "long-enough-1"))
				.andExpect(flash().attribute("error", error));
	}

	private String authorize(MockHttpSession session, String challenge) throws Exception {
		String location = mvc.perform(get(authorizeUrl("st", challenge)).session(session))
				.andExpect(status().is3xxRedirection())
				.andReturn().getResponse().getRedirectedUrl();
		assertThat(location).startsWith(REDIRECT);
		var query = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams();
		assertThat(query.getFirst("state")).isEqualTo("st");
		return query.getFirst("code");
	}

	private String exchange(String code, String verifier) throws Exception {
		return mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("grant_type", "authorization_code").param("code", code).param("redirect_uri", REDIRECT)
						.param("client_id", CLIENT).param("code_verifier", verifier))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private static URI authorizeUrl(String state, String challenge) {
		return UriComponentsBuilder.fromPath("/oauth2/authorize")
				.queryParam("response_type", "code")
				.queryParam("client_id", CLIENT)
				.queryParam("redirect_uri", REDIRECT)
				.queryParam("scope", "openid profile email")
				.queryParam("state", state)
				.queryParam("code_challenge", challenge)
				.queryParam("code_challenge_method", "S256")
				.encode().build().toUri();
	}

	private static String verifier() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String challenge(String verifier) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

}
