package security;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the browser signs in: the Core Platform's own OpenID Connect discovery document
 * ({@code <issuer>/.well-known/openid-configuration}) names its sign-in, token and sign-out addresses, so this module
 * assumes nothing about which product runs it. Read on first use rather than at startup, so the Core Platform may start
 * after this app; kept once read.
 */
@Component
public class IssuerDiscovery {

	private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
	};

	/** The addresses the browser needs. {@code endSession} is blank when the Core Platform has no sign-out endpoint. */
	public record Endpoints(String authorization, String token, String endSession) {
	}

	private final RestClient http;
	private final String issuer;
	private volatile Endpoints known;

	@Autowired
	public IssuerDiscovery(@Value("${scheduling.auth.issuer-uri:}") String issuer) {
		this(RestClient.builder().requestFactory(withTimeouts()), issuer);
	}

	IssuerDiscovery(RestClient.Builder http, String issuer) {
		this.http = http.build();
		this.issuer = issuer.replaceAll("/+$", "");
	}

	/** @throws ResponseStatusException 503 with a plain-language reason when the Core Platform cannot say */
	public Endpoints endpoints() {
		Endpoints endpoints = known;
		if (endpoints != null) {
			return endpoints;
		}
		if (issuer.isBlank()) {
			throw unavailable("No Core Platform is configured to sign in with.");
		}
		Map<String, Object> document;
		try {
			document = http.get().uri(issuer + "/.well-known/openid-configuration").retrieve().body(JSON_OBJECT);
		}
		catch (RestClientException e) {
			throw unavailable("The Core Platform at " + issuer + " cannot be reached, so you cannot sign in yet. "
					+ "Check that it is running, then try again.");
		}
		String authorization = text(document, "authorization_endpoint");
		String token = text(document, "token_endpoint");
		if (authorization.isEmpty() || token.isEmpty()) {
			throw unavailable("The Core Platform at " + issuer + " does not name its sign-in addresses.");
		}
		endpoints = new Endpoints(authorization, token, text(document, "end_session_endpoint"));
		known = endpoints;
		return endpoints;
	}

	private static String text(Map<String, Object> document, String name) {
		return document != null && document.get(name) instanceof String value ? value : "";
	}

	private static ResponseStatusException unavailable(String reason) {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
	}

	private static SimpleClientHttpRequestFactory withTimeouts() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(5));
		return factory;
	}

}
