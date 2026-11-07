package signin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Local sign-in (profile {@code local}): the app plays the Core Platform itself, with Spring Authorization Server, so
 * one process gives a normal username-and-password sign-in. The browser runs the same OIDC flow it would against the
 * real Core Platform, and the API checks the same JWT, so nothing else in the module changes. In any other profile
 * none of this exists, and tokens come from the external issuer.
 *
 * <p>Filter chains, ahead of the API chain in {@code security.SecurityConfig}: the OAuth2/OIDC endpoints (authorize,
 * token, JWKS, discovery, logout), then the sign-in form and account pages. The issuer is the address the app is
 * opened at, so localhost and 127.0.0.1 both work without cross-origin calls.
 */
@Configuration
@Profile("local")
@EnableConfigurationProperties(SignInProperties.class)
public class SignInSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SignInSecurityConfig.class);

	/**
	 * The {@code aud} of access tokens, the same setting a real Core Platform's tokens are checked against. The API
	 * accepts only this, so an ID token (whose {@code aud} is the client id) cannot be used to call it.
	 */
	private static final String API_AUDIENCE = "${spring.security.oauth2.resourceserver.jwt.audiences}";

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerEndpoints(HttpSecurity http, AccountStore accounts) throws Exception {
		http
			.oauth2AuthorizationServer(server -> {
				http.securityMatcher(server.getEndpointsMatcher());
				server.oidc(oidc -> oidc.logoutEndpoint(logout -> logout.errorResponseHandler(SignInSecurityConfig::signOutAnyway)));
			})
			.addFilterBefore(new DisabledAccountFilter(accounts), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			// Not signed in yet: send a browser to the sign-in form.
			.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
		return http.build();
	}

	/**
	 * Logout fails when it does not know the ID token, as after a restart (issued tokens are kept in memory). End this
	 * browser's sign-in session anyway and return to the app, which then asks to sign in, instead of an error page.
	 */
	private static void signOutAnyway(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException failure) throws IOException {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		response.sendRedirect("/");
	}

	@Bean
	@Order(2)
	public SecurityFilterChain signInPages(HttpSecurity http, AccountStore accounts) throws Exception {
		http
			.securityMatcher("/login", "/logout", "/account/**", "/admin/**")
			.addFilterBefore(new DisabledAccountFilter(accounts), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/login").permitAll()
				.requestMatchers("/admin/**").hasRole(AccountDirectory.ADMIN)
				.anyRequest().authenticated())
			.formLogin(form -> form.loginPage("/login"))
			.logout(logout -> logout.logoutSuccessUrl("/login?logout"));
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	public RegisteredClientRepository registeredClients(SignInProperties properties,
			@Value("${scheduling.auth.client-id}") String clientId, @Value("${scheduling.auth.scope}") String scope) {
		RegisteredClient client = RegisteredClient.withId(clientId)
				.clientId(clientId)
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public client: PKCE protects the code
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUris(uris -> uris.addAll(properties.redirectUris()))
				.postLogoutRedirectUris(uris -> uris.addAll(properties.postLogoutRedirectUris()))
				// the same scopes the browser asks for (/auth/config)
				.scopes(scopes -> scopes.addAll(List.of(scope.trim().split("\\s+"))))
				.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
				.tokenSettings(TokenSettings.builder().accessTokenTimeToLive(properties.accessTokenTtl()).build())
				.build();
		return new InMemoryRegisteredClientRepository(client);
	}

	/** No fixed issuer: it is the address each request came in on. */
	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings.builder().build();
	}

	/**
	 * Issued codes and tokens, kept in memory: tokens are checked by signature, so this only matters for logout, which
	 * looks the ID token up. After a restart it no longer finds it, which {@link #signOutAnyway} handles.
	 */
	@Bean
	public OAuth2AuthorizationService authorizationService() {
		return new InMemoryOAuth2AuthorizationService();
	}

	@Bean
	public JWKSource<SecurityContext> jwkSource(SignInProperties properties) throws IOException, JOSEException {
		return new ImmutableJWKSet<>(new JWKSet(loadOrCreateKey(properties.signingKeyFile())));
	}

	/**
	 * The API's token check: signed with this app's key, not expired, addressed to the API, and for an account that is
	 * still enabled, so disabling one stops it at once rather than when its token expires. The issuer is not pinned
	 * because it follows the address the app was opened at; only this app holds the signing key.
	 */
	@Bean
	public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource, @Value(API_AUDIENCE) String apiAudience,
			AccountStore accounts) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
		OAuth2TokenValidator<Jwt> enabledAccount = jwt -> accounts.byId(jwt.getSubject()).filter(Account::enabled).isPresent()
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
						"The account is disabled or no longer exists.", null));
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),
				new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
						aud -> aud != null && aud.contains(apiAudience)),
				enabledAccount));
		return decoder;
	}

	/**
	 * Puts the claims the module reads into the access token and the ID token. The access token's audience is the
	 * API, not the client, so the ID token cannot be used to call the API. Claims are stored with the authorization
	 * and read back through Jackson's type allowlist, which accepts ArrayList but not List.of / List.copyOf.
	 */
	@Bean
	public OAuth2TokenCustomizer<JwtEncodingContext> accountClaims(AccountStore accounts,
			@Value(API_AUDIENCE) String apiAudience) {
		return context -> {
			boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
			boolean idToken = "id_token".equals(context.getTokenType().getValue());
			if (!accessToken && !idToken) {
				return;
			}
			if (accessToken) {
				context.getClaims().audience(new ArrayList<>(List.of(apiAudience)));
			}
			// No departments: the API reads those from scheduler_department on every request (TR3.4).
			accounts.byId(context.getPrincipal().getName()).ifPresent(account -> context.getClaims()
					.claim("name", account.name())
					.claim("email", account.email() == null ? "" : account.email())
					.claim("role", account.role().name()));
		};
	}

	/**
	 * The token-signing key, kept in a file so sign-ins survive a restart; created on first start. It holds the
	 * private key: keep it out of version control.
	 */
	static RSAKey loadOrCreateKey(Path file) throws IOException, JOSEException {
		if (Files.exists(file)) {
			try {
				return RSAKey.parse(Files.readString(file));
			}
			catch (ParseException e) {
				throw new IllegalStateException("The signing key file " + file.toAbsolutePath() + " is not a valid RSA JWK.", e);
			}
		}
		RSAKey key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
		if (file.toAbsolutePath().getParent() != null) {
			Files.createDirectories(file.toAbsolutePath().getParent());
		}
		Files.writeString(file, key.toJSONString());
		log.info("Created a new token-signing key at {}.", file.toAbsolutePath());
		return key;
	}

}
