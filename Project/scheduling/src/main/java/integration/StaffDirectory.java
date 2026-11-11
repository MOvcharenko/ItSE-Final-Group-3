package integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import security.ProfileSync;

/**
 * The Core Platform's staff list (TR4.2), so an instructor or scheduler created there can be assigned to classes and
 * matched by schedule import before they have ever signed in here. This server reads it as its own client, with the
 * OAuth2 client-credentials grant; no person's token is involved.
 *
 * <p>Off unless {@code scheduling.directory.uri} is set (profile {@code accounts}): elsewhere profiles come only from
 * sign-in. If the Core Platform does not answer, the profiles already stored are used and the cause is logged.
 */
@Component
public class StaffDirectory {

	private static final Logger log = LoggerFactory.getLogger(StaffDirectory.class);
	/** Fetch a new token this long before the old one expires, so a request never carries an expired one. */
	private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

	private final ProfileSync profiles;
	private final RestClient http;
	private final Clock clock;
	private final String staffUri;
	private final String tokenUri;
	private final String clientId;
	private final String clientSecret;

	private String token;
	private Instant tokenExpires = Instant.MIN;

	@Autowired
	public StaffDirectory(ProfileSync profiles, Clock clock,
			@Value("${scheduling.directory.uri:}") String staffUri,
			@Value("${scheduling.directory.token-uri:}") String tokenUri,
			@Value("${scheduling.directory.client-id:}") String clientId,
			@Value("${scheduling.directory.client-secret:}") String clientSecret) {
		this(profiles, RestClient.builder(), clock, staffUri, tokenUri, clientId, clientSecret);
	}

	/** For tests, which bind a mock server to the builder. */
	StaffDirectory(ProfileSync profiles, RestClient.Builder http, Clock clock, String staffUri, String tokenUri,
			String clientId, String clientSecret) {
		this.profiles = profiles;
		this.http = http.build();
		this.clock = clock;
		this.staffUri = staffUri;
		this.tokenUri = tokenUri;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
	}

	/**
	 * Copies the Core Platform's current staff into the profiles. The remote calls run before any transaction, so a slow
	 * Core Platform never holds a database connection. Call it before listing or matching instructors.
	 */
	public void refreshProfiles() {
		if (staffUri.isBlank()) {
			return;
		}
		try {
			List<ProfileSync.Staff> staff = http.get().uri(staffUri)
					.headers(headers -> headers.setBearerAuth(token()))
					.retrieve()
					.body(new ParameterizedTypeReference<>() { });
			profiles.sync(staff == null ? List.of() : staff);
		} catch (RuntimeException e) {
			log.warn("Staff list from {} unavailable; new accounts appear once they sign in", staffUri, e);
		}
	}

	private synchronized String token() {
		Instant now = clock.instant();
		if (token == null || !now.isBefore(tokenExpires.minus(EXPIRY_MARGIN))) {
			LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
			form.add("grant_type", "client_credentials");
			form.add("scope", "directory.read");
			TokenResponse response = http.post().uri(tokenUri)
					.headers(headers -> headers.setBasicAuth(clientId, clientSecret))
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(form)
					.retrieve()
					.body(TokenResponse.class);
			if (response == null || response.accessToken() == null) {
				throw new IllegalStateException("The token endpoint " + tokenUri + " returned no access token.");
			}
			token = response.accessToken();
			tokenExpires = now.plusSeconds(response.expiresIn());
		}
		return token;
	}

	private record TokenResponse(@JsonProperty("access_token") String accessToken,
			@JsonProperty("expires_in") long expiresIn) {
	}

}
