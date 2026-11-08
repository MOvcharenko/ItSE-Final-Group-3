package security;

import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the browser which sign-in flow to use. This is not an /api endpoint and
 * it returns nothing private — just "run the dev-account picker, with these
 * test accounts" or "go through the Core Platform's OIDC flow at these addresses".
 *
 * <p>Mode {@code dev} is set by the local, dev and test profiles, where {@code
 * DevAuthConfig} serves {@code POST /dev/token}. Anywhere else the browser is
 * told to run the authorization-code flow against the issuer the resource
 * server already validates ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}),
 * at the addresses its discovery document names ({@link IssuerDiscovery}).
 * The return addresses are the browser's own ({@code js/api.js}).
 */
@RestController
public class AuthConfigController {

	/** How the Core Platform registered the browser UI. */
	@Value("${scheduling.auth.client-id}")
	private String clientId;

	@Value("${scheduling.auth.scope}")
	private String scope;

	@Value("${scheduling.auth.mode:oidc}")
	private String mode;

	/** Where people change their password; blank when the Core Platform has no page for it. */
	@Value("${scheduling.auth.account-uri:}")
	private String accountUri;

	private final IssuerDiscovery discovery;

	public AuthConfigController(IssuerDiscovery discovery) {
		this.discovery = discovery;
	}

	@GetMapping("/auth/config")
	public Map<String, Object> config() {
		if ("dev".equals(mode)) {
			return Map.of("mode", "dev", "accounts", Stream.of(DevAccount.values())
					.map(account -> new TestAccount(account.id, account.displayName, account.role.name()))
					.toList());
		}
		IssuerDiscovery.Endpoints endpoints = discovery.endpoints();
		return Map.of(
				"mode", "oidc",
				"clientId", clientId,
				"scope", scope,
				"authorizationEndpoint", endpoints.authorization(),
				"tokenEndpoint", endpoints.token(),
				// blank when the Core Platform has none: "Switch account" then only forgets the token
				"logoutUri", endpoints.endSession(),
				"accountUri", accountUri);
	}

	/** A test account the dev picker offers; signing in with it goes through POST /dev/token. */
	public record TestAccount(String id, String name, String role) {
	}

}
