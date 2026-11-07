package main.java.team3.coreplatform;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The mock Core Platform's OIDC surface. Only the authorization-code grant with
 * PKCE (S256) is supported; that is what the browser signs in with.
 */
@Controller
public class OidcController {

	private static final Duration CODE_TTL = Duration.ofSeconds(60);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final AccountStore accounts;
	private final TokenService tokens;
	private final String issuer;
	private final Map<String, AuthCode> codes = new ConcurrentHashMap<>();

	public OidcController(AccountStore accounts, TokenService tokens, @Value("${mock.issuer}") String issuer) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.issuer = issuer;
	}

	// --- discovery ---------------------------------------------------------

	@GetMapping("/.well-known/openid-configuration")
	@ResponseBody
	public Map<String, Object> discovery() {
		return Map.of(
				"issuer", issuer,
				"authorization_endpoint", issuer + "/oauth2/authorize",
				"token_endpoint", issuer + "/oauth2/token",
				"jwks_uri", issuer + "/.well-known/jwks.json",
				"response_types_supported", List.of("code"),
				"grant_types_supported", List.of("authorization_code"),
				"subject_types_supported", List.of("public"),
				"id_token_signing_alg_values_supported", List.of("RS256"),
				"code_challenge_methods_supported", List.of("S256"));
	}

	@GetMapping("/.well-known/jwks.json")
	@ResponseBody
	public Map<String, Object> jwks() {
		return tokens.publicJwks().toJSONObject();
	}

	// --- authorize ---------------------------------------------------------

	@GetMapping(value = "/oauth2/authorize", produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public String authorizeForm(
			@RequestParam("client_id") String clientId,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam("state") String state,
			@RequestParam("code_challenge") String codeChallenge,
			@RequestParam(value = "code_challenge_method", defaultValue = "S256") String method) {
		if (!"S256".equals(method)) {
			return errorPage("Only the S256 PKCE method is supported.");
		}
		return signInPage(clientId, redirectUri, state, codeChallenge, method);
	}

	@PostMapping("/oauth2/authorize")
	public void authorizeSubmit(
			@RequestParam("client_id") String clientId,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam("state") String state,
			@RequestParam("code_challenge") String codeChallenge,
			@RequestParam(value = "code_challenge_method", defaultValue = "S256") String method,
			@RequestParam("account") String accountId,
			HttpServletResponse response) throws Exception {
		if (!"S256".equals(method)) {
			response.setStatus(400);
			response.getWriter().write(errorPage("Only the S256 PKCE method is supported."));
			return;
		}
		Account account = accounts.find(accountId).orElse(null);
		if (account == null) {
			response.setStatus(404);
			response.getWriter().write(errorPage("Unknown account: " + accountId));
			return;
		}
		String code = randomToken();
		codes.put(code, new AuthCode(account, redirectUri, codeChallenge, Instant.now().plus(CODE_TTL)));
		String separator = redirectUri.contains("?") ? "&" : "?";
		response.sendRedirect(redirectUri + separator + "code=" + url(code) + "&state=" + url(state));
	}

	// --- token -------------------------------------------------------------

	@PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	@ResponseBody
	public ResponseEntity<Map<String, Object>> token(
			@RequestParam("grant_type") String grantType,
			@RequestParam("code") String code,
			@RequestParam("redirect_uri") String redirectUri,
			@RequestParam("code_verifier") String codeVerifier,
			@RequestParam("client_id") String clientId) throws Exception {
		if (!"authorization_code".equals(grantType)) {
			return ResponseEntity.badRequest().body(Map.of("error", "unsupported_grant_type"));
		}
		AuthCode stored = codes.remove(code);
		if (stored == null || Instant.now().isAfter(stored.expiresAt())) {
			return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
		}
		if (!stored.redirectUri().equals(redirectUri)) {
			return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
		}
		if (!s256(codeVerifier).equals(stored.codeChallenge())) {
			return ResponseEntity.badRequest().body(Map.of("error", "invalid_grant"));
		}
		String accessToken = tokens.issue(stored.account());
		return ResponseEntity.ok(Map.of(
				"access_token", accessToken,
				"token_type", "Bearer",
				"expires_in", tokens.ttl().toSeconds()));
	}

	// --- helpers -----------------------------------------------------------

	private record AuthCode(Account account, String redirectUri, String codeChallenge, Instant expiresAt) {
	}

	private String signInPage(String clientId, String redirectUri, String state, String codeChallenge, String method) {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		html.append("<title>Sign in · Core Platform</title>");
		html.append("<style>");
		html.append("*{box-sizing:border-box}");
		html.append("body{margin:0;font-family:'IBM Plex Sans',system-ui,-apple-system,sans-serif;");
		html.append("background:#f4f6fa;color:#1f2937;display:flex;justify-content:center;");
		html.append("align-items:center;min-height:100vh;padding:24px}");
		html.append("main{background:#fff;border-radius:12px;padding:32px;width:min(420px,100%);");
		html.append("box-shadow:0 8px 24px rgba(15,23,42,.08)}");
		html.append("h1{margin:0 0 4px;font-size:22px}");
		html.append("p.lead{margin:0 0 24px;color:#6b7280;font-size:14px}");
		html.append("form{margin:0}");
		html.append("button.account{display:flex;gap:12px;align-items:center;width:100%;");
		html.append("padding:14px;margin:8px 0;border:1px solid #e5e7eb;background:#fff;");
		html.append("border-radius:10px;cursor:pointer;text-align:left;font-family:inherit;font-size:14px}");
		html.append("button.account:hover{background:#f9fafb;border-color:#c7d2fe}");
		html.append(".avatar{width:36px;height:36px;border-radius:50%;background:#e0e7ff;color:#3730a3;");
		html.append("display:grid;place-items:center;font-weight:600;flex-shrink:0}");
		html.append(".name{font-weight:600;display:block}");
		html.append(".role{color:#6b7280;font-size:12px;text-transform:uppercase;letter-spacing:.04em}");
		html.append("footer{margin-top:20px;font-size:12px;color:#9ca3af;text-align:center}");
		html.append("</style></head><body><main>");
		html.append("<h1>Core Platform</h1>");
		html.append("<p class=\"lead\">Sign in to continue to Academic Scheduling.</p>");
		for (Account account : accounts.all()) {
			html.append("<form method=\"post\" action=\"/oauth2/authorize\">");
			html.append(hidden("client_id", clientId));
			html.append(hidden("redirect_uri", redirectUri));
			html.append(hidden("state", state));
			html.append(hidden("code_challenge", codeChallenge));
			html.append(hidden("code_challenge_method", method));
			html.append(hidden("account", account.id()));
			html.append("<button class=\"account\" type=\"submit\">");
			html.append("<span class=\"avatar\">").append(HtmlUtils.htmlEscape(account.initials())).append("</span>");
			html.append("<span><span class=\"name\">").append(HtmlUtils.htmlEscape(account.name()))
					.append("</span>");
			html.append("<span class=\"role\">").append(HtmlUtils.htmlEscape(account.role())).append("</span></span>");
			html.append("</button></form>");
		}
		html.append("<footer>Mock Core Platform · for local development only</footer>");
		html.append("</main></body></html>");
		return html.toString();
	}

	private static String hidden(String name, String value) {
		return "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(name) + "\" value=\""
				+ HtmlUtils.htmlEscape(value) + "\">";
	}

	private static String errorPage(String message) {
		return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Sign in failed</title></head>"
				+ "<body style=\"font-family:system-ui;padding:32px\"><h1>Sign in failed</h1><p>"
				+ HtmlUtils.htmlEscape(message) + "</p></body></html>";
	}

	private static String randomToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String s256(String input) throws NoSuchAlgorithmException {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.US_ASCII));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
	}

	private static String url(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}