package signin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;

import app.SchedulingApplication;

/**
 * The built-in sign-in's session against a real server, as a browser keeps it: one cookie, and the UI's API calls
 * sent several at once. MockMvc does not show the problem this guards against, so this uses real HTTP.
 */
@SpringBootTest(classes = SchedulingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.datasource.url=jdbc:h2:mem:local-signin-session;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
				"scheduling.signin.signing-key-file=${java.io.tmpdir}/scheduling-signin-test-${random.uuid}/signing-key.json",
				// the code exchange only compares this text; nothing is opened at it
				"scheduling.signin.redirect-uris=" + LocalSignInSessionIT.REDIRECT })
@ActiveProfiles("local")
class LocalSignInSessionIT {

	static final String REDIRECT = "http://localhost:8080/signin-callback.html";
	private static final String CLIENT = "scheduling-web"; // scheduling.auth.client-id's default
	private static final List<String> SCREEN_LOAD = List.of("/api/terms", "/api/subjects", "/api/rooms", "/api/equipment",
			"/api/instructors");

	@LocalServerPort
	int port;

	private final CookieManager cookies = new CookieManager();
	private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
			.followRedirects(HttpClient.Redirect.NEVER).build();
	/** The PKCE verifier of the last authorization request. */
	private String verifier;

	@Test
	@DisplayName("API calls, even several at once, leave the sign-in session alone, so signing in again needs no password")
	void apiCallsKeepTheSignInSession() throws Exception {
		signIn("m.brooks", "Scheduler#2026");
		String token = JsonPath.read(exchangeForToken(authorizeForCode()), "$.access_token");
		String sessionBefore = sessionId();

		for (int round = 0; round < 5; round++) {
			CompletableFuture.allOf(SCREEN_LOAD.stream()
					.map(path -> http.sendAsync(get(path).header("Authorization", "Bearer " + token).build(),
							HttpResponse.BodyHandlers.discarding()))
					.toArray(CompletableFuture[]::new)).join();
		}

		assertThat(sessionId()).isEqualTo(sessionBefore);
		assertThat(authorizeForCode()).isNotNull(); // still signed in: a code, not the sign-in form
	}

	private void signIn(String username, String password) throws Exception {
		String page = http.send(get("/login").build(), HttpResponse.BodyHandlers.ofString()).body();
		Matcher csrf = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page);
		assertThat(csrf.find()).isTrue();
		HttpResponse<Void> response = http.send(form("/login",
				Map.of("username", username, "password", password, "_csrf", csrf.group(1))),
				HttpResponse.BodyHandlers.discarding());
		assertThat(response.headers().firstValue("Location")).hasValueSatisfying(to -> assertThat(to).endsWith("/"));
	}

	/** The code from an authorization request, or null when the server asks to sign in instead. */
	private String authorizeForCode() throws Exception {
		verifier = random();
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		String query = query(Map.of("response_type", "code", "client_id", CLIENT,
				"redirect_uri", REDIRECT, "scope", "openid profile email", "state", "st", "code_challenge", challenge,
				"code_challenge_method", "S256"));
		String location = http.send(get("/oauth2/authorize?" + query).header("Accept", "text/html").build(),
				HttpResponse.BodyHandlers.discarding()).headers().firstValue("Location").orElse("");
		Matcher code = Pattern.compile("[?&]code=([^&]+)").matcher(location);
		return location.startsWith(REDIRECT) && code.find() ? code.group(1) : null;
	}

	private String exchangeForToken(String code) throws Exception {
		assertThat(code).isNotNull();
		return http.send(form("/oauth2/token", Map.of("grant_type", "authorization_code", "code", code,
				"redirect_uri", REDIRECT, "client_id", CLIENT, "code_verifier", verifier)),
				HttpResponse.BodyHandlers.ofString()).body();
	}

	private String sessionId() {
		return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
				.map(HttpCookie::getValue).findFirst().orElseThrow();
	}

	private HttpRequest.Builder get(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
	}

	private HttpRequest form(String path, Map<String, String> fields) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(query(fields))).build();
	}

	private static String query(Map<String, String> fields) {
		return fields.entrySet().stream()
				.map(field -> field.getKey() + "=" + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
				.collect(Collectors.joining("&"));
	}

	private static String random() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
