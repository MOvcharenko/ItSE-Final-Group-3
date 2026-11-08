package accounts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpSession;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * The sign-in server, with Spring Authorization Server: the Academic Scheduling UI signs people in here with the
 * authorization-code flow and PKCE, and the scheduling module checks the tokens against this server's published key
 * (its {@code issuer-uri} is {@link AccountsProperties#issuer()}).
 *
 * <p>Two filter chains: the OAuth2/OIDC endpoints (authorize, token, JWKS, discovery, logout), then the sign-in form
 * and account pages.
 */
@Configuration
@EnableConfigurationProperties(AccountsProperties.class)
public class SignInSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SignInSecurityConfig.class);

	@Bean
	@Order(1)
	public SecurityFilterChain authorizationServerEndpoints(HttpSecurity http, AccountStore accounts,
			AccountsProperties properties) throws Exception {
		http
			.oauth2AuthorizationServer(server -> {
				// The endpoints, plus the browser's CORS preflight for the token endpoint, which they do not match.
				http.securityMatcher(new OrRequestMatcher(server.getEndpointsMatcher(),
						PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.OPTIONS, "/oauth2/token")));
				// Logout fails when it does not know the ID token, as after a restart (issued tokens are kept in memory).
				// End this browser's sign-in session anyway and return to the app, which then asks to sign in.
				server.oidc(oidc -> oidc.logoutEndpoint(logout -> logout.errorResponseHandler((request, response, failure) -> {
					HttpSession session = request.getSession(false);
					if (session != null) {
						session.invalidate();
					}
					response.sendRedirect(properties.appHome());
				})));
			})
			// The scheduling UI runs on another origin and exchanges the code from the browser.
			.cors(Customizer.withDefaults())
			.addFilterBefore(new DisabledAccountFilter(accounts), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			// Not signed in yet: send a browser to the sign-in form.
			.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
					new LoginUrlAuthenticationEntryPoint("/login"), new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
		return http.build();
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

	/** Only the token endpoint is called cross-origin; everything else is a browser navigation. */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(AccountsProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.appUrls());
		cors.setAllowedMethods(List.of("POST"));
		cors.setAllowedHeaders(List.of("Content-Type"));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/oauth2/token", cors);
		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	public RegisteredClientRepository registeredClients(AccountsProperties properties) {
		RegisteredClient client = RegisteredClient.withId(properties.clientId())
				.clientId(properties.clientId())
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public client: PKCE protects the code
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUris(uris -> uris.addAll(properties.redirectUris()))
				.postLogoutRedirectUris(uris -> uris.addAll(properties.postLogoutRedirectUris()))
				.scopes(scopes -> scopes.addAll(List.of(properties.scope().trim().split("\\s+"))))
				.clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
				.tokenSettings(TokenSettings.builder().accessTokenTimeToLive(properties.accessTokenTtl()).build())
				.build();
		return new InMemoryRegisteredClientRepository(client);
	}

	/** A fixed issuer: the scheduling module checks every token's {@code iss} against it. */
	@Bean
	public AuthorizationServerSettings authorizationServerSettings(AccountsProperties properties) {
		return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
	}

	/**
	 * Issued codes and tokens, kept in memory: tokens are checked by signature, so this only matters for logout, which
	 * looks the ID token up. After a restart it no longer finds it, which the logout error handler above handles.
	 */
	@Bean
	public OAuth2AuthorizationService authorizationService() {
		return new InMemoryOAuth2AuthorizationService();
	}

	@Bean
	public JWKSource<SecurityContext> jwkSource(AccountsProperties properties) throws IOException, JOSEException {
		return new ImmutableJWKSet<>(new JWKSet(loadOrCreateKey(properties.signingKeyFile())));
	}

	/**
	 * Puts the claims the scheduling module reads into the access token and the ID token. The access token's audience
	 * is the API, not the client, so the ID token cannot be used to call it. Claims are stored with the authorization
	 * and read back through Jackson's type allowlist, which accepts ArrayList but not List.of / List.copyOf.
	 */
	@Bean
	public OAuth2TokenCustomizer<JwtEncodingContext> accountClaims(AccountStore accounts, AccountsProperties properties) {
		return context -> {
			boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
			boolean idToken = "id_token".equals(context.getTokenType().getValue());
			if (!accessToken && !idToken) {
				return;
			}
			if (accessToken) {
				context.getClaims().audience(new ArrayList<>(List.of(properties.apiAudience())));
			}
			accounts.byId(context.getPrincipal().getName()).ifPresent(account -> {
				context.getClaims()
						.claim("name", account.name())
						.claim("email", account.email() == null ? "" : account.email())
						.claim("role", account.role().name());
				if (account.role() == Role.SCHEDULER) {
					context.getClaims().claim("departments", new ArrayList<>(account.departments()));
				}
				if (account.homeDepartment() != null) {
					context.getClaims().claim("department", account.homeDepartment());
				}
			});
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
