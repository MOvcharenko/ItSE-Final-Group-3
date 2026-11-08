package accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The sign-in server as the Academic Scheduling UI uses it: the sign-in form, authorization code with PKCE, the
 * cross-origin token exchange, and the claims the scheduling module reads; then the account pages. Tests that change
 * an account make their own, so they do not depend on each other.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:accounts-test",
		"accounts.signing-key-file=${java.io.tmpdir}/account-service-test-${random.uuid}/signing-key.json" })
@AutoConfigureMockMvc
class SignInIT {

	private static final String ISSUER = "http://localhost:9000";
	private static final String CLIENT = "scheduling-web";
	private static final String APP = "http://localhost:8080";
	private static final String REDIRECT = APP + "/signin-callback.html";

	@Autowired
	MockMvc mvc;

	@Autowired
	AccountService service;

	@Autowired
	AccountStore accounts;

	@Autowired
	RegisteredClientRepository clients;

	@Autowired
	JWKSource<SecurityContext> keys;

	// --- what the scheduling module and its UI are given ------------------

	@Test
	@DisplayName("The discovery document names the fixed issuer and the endpoints the scheduling module hands its UI")
	void discovery() throws Exception {
		mvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value(ISSUER))
				.andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
				.andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
				.andExpect(jsonPath("$.end_session_endpoint").value(ISSUER + "/connect/logout"))
				.andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"));
	}

	@Test
	@DisplayName("The scheduling UI is registered with its sign-in and sign-out return addresses from accounts.app-urls")
	void registration() {
		RegisteredClient client = clients.findByClientId(CLIENT);
		assertThat(client.getRedirectUris()).containsExactlyInAnyOrder(
				"http://localhost:8080/signin-callback.html", "http://127.0.0.1:8080/signin-callback.html");
		assertThat(client.getPostLogoutRedirectUris()).containsExactlyInAnyOrder(
				"http://localhost:8080/", "http://127.0.0.1:8080/");
		assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
	}

	@Test
	@DisplayName("The UI may call the token endpoint from its own origin, and no other origin may")
	void cors() throws Exception {
		mvc.perform(options("/oauth2/token").header("Origin", APP).header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", APP));
		mvc.perform(options("/oauth2/token").header("Origin", "http://evil.example").header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

	// --- sign-in and tokens -----------------------------------------------

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
	@DisplayName("A scheduler's access token is signed by this server, for the API, with sub, name, role and departments")
	void schedulerToken() throws Exception {
		JWTClaimsSet claims = verified(accessToken("m.brooks", "Scheduler#2026"));
		assertThat(claims.getIssuer()).isEqualTo(ISSUER);
		assertThat(claims.getSubject()).isEqualTo("U01");
		assertThat(claims.getAudience()).containsExactly("scheduling-api");
		assertThat(claims.getStringClaim("name")).isEqualTo("Maya Brooks");
		assertThat(claims.getStringClaim("role")).isEqualTo("SCHEDULER");
		assertThat(claims.getStringListClaim("departments")).containsExactlyInAnyOrder("CS", "MATH");
		assertThat(claims.getClaims()).doesNotContainKey("department");
	}

	@Test
	@DisplayName("An instructor's token carries the home department and no departments to manage; a student's carries neither")
	void instructorAndStudentTokens() throws Exception {
		JWTClaimsSet instructor = verified(accessToken("l.bianchi", "Instructor#2026"));
		assertThat(instructor.getStringClaim("role")).isEqualTo("INSTRUCTOR");
		assertThat(instructor.getStringClaim("department")).isEqualTo("MATH");
		assertThat(instructor.getClaims()).doesNotContainKey("departments");

		JWTClaimsSet student = verified(accessToken("j.ellis", "Student#2026"));
		assertThat(student.getStringClaim("role")).isEqualTo("STUDENT");
		assertThat(student.getClaims()).doesNotContainKeys("department", "departments");
	}

	@Test
	@DisplayName("The ID token is addressed to the client, not the API, so the scheduling module refuses it")
	void idTokenIsForTheClient() throws Exception {
		String verifier = verifier();
		String body = exchange(authorize(signIn("l.bianchi", "Instructor#2026"), challenge(verifier)), verifier);
		String idToken = JsonPath.read(body, "$.id_token");

		assertThat(verified(idToken).getAudience()).containsExactly(CLIENT);
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
				.queryParam("post_logout_redirect_uri", APP + "/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(redirectedUrl(APP + "/"));

		mvc.perform(get(authorizeUrl("s", challenge(verifier()))).session(session).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	@DisplayName("Logout with an ID token it does not know (as after a restart) still signs out and returns to the app")
	void logoutWithUnknownIdToken() throws Exception {
		MockHttpSession session = signIn("m.brooks", "Scheduler#2026");
		URI logout = UriComponentsBuilder.fromPath("/connect/logout")
				.queryParam("id_token_hint", "issued-before-a-restart")
				.queryParam("post_logout_redirect_uri", APP + "/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(redirectedUrl(APP + "/"));
		assertThat(session.isInvalid()).isTrue();
	}

	@Test
	@DisplayName("Switch account without an ID token (logout names the client) still signs out, so the next sign-in asks again")
	void logoutWithoutIdToken() throws Exception {
		MockHttpSession session = signIn("m.brooks", "Scheduler#2026");
		URI logout = UriComponentsBuilder.fromPath("/connect/logout")
				.queryParam("client_id", CLIENT)
				.queryParam("post_logout_redirect_uri", APP + "/")
				.encode().build().toUri();
		mvc.perform(get(logout).session(session)).andExpect(status().is3xxRedirection());
		assertThat(session.isInvalid()).isTrue();
		mvc.perform(get(authorizeUrl("st", challenge(verifier()))).accept(MediaType.TEXT_HTML))
				.andExpect(redirectedUrl("/login"));
	}

	// --- accounts -----------------------------------------------------------

	@Test
	@DisplayName("First start creates the demo accounts, with hashed passwords, Maya's departments and Bianchi's home department")
	void seeded() {
		assertThat(accounts.all()).extracting(Account::id).contains("U01", "U02", "U03");
		Account maya = accounts.byId("U01").orElseThrow();
		assertThat(maya.password()).startsWith("{bcrypt}");
		assertThat(maya.admin()).isTrue();
		assertThat(maya.departments()).containsExactlyInAnyOrder("CS", "MATH");
		assertThat(maya.homeDepartment()).isNull();
		assertThat(accounts.byId("U02").orElseThrow().homeDepartment()).isEqualTo("MATH");
		assertThat(accounts.byId("U03").orElseThrow().departments()).isEmpty();
	}

	@Test
	@DisplayName("This server's own address opens the account page, which links back to Academic Scheduling")
	void homeAndBackLink() throws Exception {
		mvc.perform(get("/")).andExpect(redirectedUrl("/account"));
		mvc.perform(get("/account").session(signIn("l.bianchi", "Instructor#2026")))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("href=\"" + APP + "/\"")));
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
	@DisplayName("An administrator creates a scheduler, who signs in with their departments in the token")
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
		JWTClaimsSet claims = verified(accessToken(id.toLowerCase(), "first-password"));
		assertThat(claims.getSubject()).isEqualTo(id);
		assertThat(claims.getStringListClaim("departments")).containsExactlyInAnyOrder("CS", "PHYS");
	}

	@Test
	@DisplayName("An instructor's home department is kept; a scheduler's is ignored")
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

		assertThat(accounts.byId(instructor).orElseThrow().homeDepartment()).isEqualTo("PHYS");
		assertThat(accounts.byId(scheduler).orElseThrow().homeDepartment()).isNull();
	}

	@Test
	@DisplayName("A name longer than the scheduling module's profiles hold is refused")
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
	@DisplayName("Disabling a signed-in account stops it at once: its sign-in session hands out no more tokens, and its account pages close")
	void disableWhileSignedIn() throws Exception {
		String username = newAccount("INSTRUCTOR", true, "first-password");
		String id = accounts.byUsername(username).orElseThrow().id();
		MockHttpSession session = signIn(username, "first-password");
		MockHttpSession adminPages = signIn(username, "first-password");
		mvc.perform(get("/admin/accounts").session(adminPages)).andExpect(status().isOk());

		mvc.perform(post("/admin/accounts/" + id + "/enabled").session(signIn("m.brooks", "Scheduler#2026")).with(csrf())
				.param("enabled", "false"));

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

	/** Signs in, then runs the browser's authorization-code flow; returns the access token. */
	private String accessToken(String username, String password) throws Exception {
		String verifier = verifier();
		return JsonPath.read(exchange(authorize(signIn(username, password), challenge(verifier)), verifier), "$.access_token");
	}

	/** The token's claims, after checking it is signed with this server's key, as the scheduling module checks it. */
	private JWTClaimsSet verified(String token) throws Exception {
		SignedJWT jwt = SignedJWT.parse(token);
		RSAKey key = (RSAKey) keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(jwt.getHeader().getKeyID()).build()), null)
				.get(0);
		assertThat(jwt.verify(new RSASSAVerifier(key))).isTrue();
		return jwt.getJWTClaimsSet();
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
