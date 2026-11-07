package config;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the browser which sign-in flow to use. This is not an /api endpoint and
 * it returns nothing private — just "run the dev-account picker" or "go through
 * the Core Platform's OIDC flow at this issuer".
 *
 * <p>Mode {@code dev} is set by the dev and test profiles, where {@code
 * DevAuthConfig} serves {@code POST /dev/token}. Anywhere else the browser is
 * told to run the authorization-code flow against the issuer the resource
 * server already validates ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}).
 */
@RestController
public class AuthConfigController {

	@Value("${scheduling.auth.mode:oidc}")
	private String mode;

	@Value("${scheduling.auth.issuer-uri:}")
	private String issuerUri;

	@Value("${scheduling.auth.client-id:scheduling-web}")
	private String clientId;

	@Value("${scheduling.auth.redirect-uri:http://localhost:8080/signin-callback.html}")
	private String redirectUri;

	@Value("${scheduling.auth.scope:openid profile email}")
	private String scope;

	@GetMapping("/auth/config")
	public Map<String, String> config() {
		if ("dev".equals(mode)) {
			return Map.of("mode", "dev");
		}
		return Map.of(
				"mode", "oidc",
				"issuer", issuerUri,
				"clientId", clientId,
				"redirectUri", redirectUri,
				"scope", scope);
	}

}