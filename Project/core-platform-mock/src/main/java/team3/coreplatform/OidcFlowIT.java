package main.java.team3.coreplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

/**
 * End-to-end check of the mock Core Platform: discovery, authorize (form and
 * complete), token exchange, and a real signature check on the issued token
 * using the key the JWKS endpoint publishes.
 */
@SpringBootTest(classes = CorePlatformMockApplication.class)
@AutoConfigureMockMvc
class OidcFlowIT {

	private static final String REDIRECT = "http://localhost:8080/signin-callback.html";
	private static final Pattern CODE = Pattern.compile("[?&]code=([^&]+)");
	private static final Pattern STATE = Pattern.compile("[?&]state=([^&]+)");

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("The discovery document names the expected endpoints")
	void discovery() throws Exception {
		String body = mvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<String>read(body, "$.issuer")).startsWith("http://");
		assertThat(JsonPath.<String>read(body, "$.authorization_endpoint")).endsWith("/oauth2/authorize");
		assertThat(JsonPath.<String>read(body, "$.token_endpoint")).endsWith("/oauth2/token");
		assertThat(JsonPath.<String>read(body, "$.jwks_uri")).endsWith("/.well-known/jwks.json");
	}

	@Test
	@DisplayName("The authorize page lists the three test accounts")
	void authorizePageListsAccounts() throws Exception {
		MvcResult result = mvc.perform(get("/oauth2/authorize")
						.param("client_id", "scheduling-web")
						.param("redirect_uri", REDIRECT)
						.param("state", "st")
						.param("code_challenge", "cc")
						.param("code_challenge_method", "S256"))
				.andExpect(status().isOk())
				.andReturn();

		String html = result.getResponse().getContentAsString();
		assertThat(html).contains("Maya Brooks", "L. Bianchi", "Jordan Ellis");
	}

	@Test
	@DisplayName("The full authorization-code flow returns a token whose claims are what the app reads")
	void fullFlow() throws Exception {
		String verifier = randomVerifier();
		String challenge = s256(verifier);
		String state = "state-123";

		MvcResult authorize = mvc.perform(post("/oauth2/authorize")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("client_id", "scheduling-web")
						.param("redirect_uri", REDIRECT)
						.param("state", state)
						.param("code_challenge", challenge)
						.param("code_challenge_method", "S256")
						.param("account", "U02"))
				.andExpect(status().isFound())
				.andReturn();

		String location = authorize.getResponse().getRedirectedUrl();
		assertThat(location).startsWith(REDIRECT);
		String code = extract(CODE, location);
		assertThat(extract(STATE, location)).isEqualTo(state);

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", REDIRECT);
		form.add("code_verifier", verifier);
		form.add("client_id", "scheduling-web");

		String body = mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).params(form))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		String token = JsonPath.read(body, "$.access_token");
		assertThat(JsonPath.<String>read(body, "$.token_type")).isEqualTo("Bearer");

		// Signature validates against the JWKS the mock publishes.
		SignedJWT jwt = SignedJWT.parse(token);
		Map<String, Object> jwks = JsonPath.read(
				mvc.perform(get("/.well-known/jwks.json")).andReturn().getResponse().getContentAsString(), "$");
		RSAKey publicKey = (RSAKey) new JWKSet(JsonPath.read(jwks, "$")).getKeys().getFirst();
		assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();

		// Claims the scheduling module reads.
		assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("U02");
		assertThat(jwt.getJWTClaimsSet().getStringClaim("name")).isEqualTo("L. Bianchi");
		assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("INSTRUCTOR");
	}

	@Test
	@DisplayName("A wrong PKCE verifier is rejected")
	void wrongVerifier() throws Exception {
		String challenge = s256("the-right-verifier");

		String location = mvc.perform(post("/oauth2/authorize")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("client_id", "scheduling-web")
						.param("redirect_uri", REDIRECT)
						.param("state", "s")
						.param("code_challenge", challenge)
						.param("code_challenge_method", "S256")
						.param("account", "U03"))
				.andReturn().getResponse().getRedirectedUrl();
		String code = extract(CODE, location);

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", REDIRECT);
		form.add("code_verifier", "not-the-right-verifier");
		form.add("client_id", "scheduling-web");

		mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).params(form))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("An authorization code can only be used once")
	void codeIsSingleUse() throws Exception {
		String verifier = randomVerifier();
		String challenge = s256(verifier);

		String location = mvc.perform(post("/oauth2/authorize")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("client_id", "scheduling-web")
						.param("redirect_uri", REDIRECT)
						.param("state", "s")
						.param("code_challenge", challenge)
						.param("code_challenge_method", "S256")
						.param("account", "U01"))
				.andReturn().getResponse().getRedirectedUrl();
		String code = extract(CODE, location);

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("redirect_uri", REDIRECT);
		form.add("code_verifier", verifier);
		form.add("client_id", "scheduling-web");

		mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).params(form))
				.andExpect(status().isOk());
		mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).params(form))
				.andExpect(status().isBadRequest());
	}

	private static String randomVerifier() {
		byte[] bytes = new byte[32];
		new java.security.SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String s256(String value) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	private static String extract(Pattern pattern, String url) {
		Matcher matcher = pattern.matcher(URI.create(url).getQuery());
		if (!matcher.find()) throw new IllegalStateException("No match in " + url);
		return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
	}

}