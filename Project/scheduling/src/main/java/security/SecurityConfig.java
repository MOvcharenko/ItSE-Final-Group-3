package security;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import domain.Role;

/**
 * Resource server: the module has no login of its own (TR4.2). Every request except health needs a Core
 * Platform JWT, and the role comes only from that token (NFR1.4). Spring Boot builds the {@code JwtDecoder}
 * from the issuer URI (see {@link IssuerRequiredConfig}); {@link DevAuthConfig} supplies it in local, dev and test.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	// The claims this module reads. The real Core Platform's names are still to be confirmed.
	static final String ROLE_CLAIM = "role";
	static final String NAME_CLAIM = "name";
	static final String EMAIL_CLAIM = "email";
	/** A scheduler's departments (TR3.4), a list. Without it they come from scheduler_department. */
	static final String DEPARTMENTS_CLAIM = "departments";
	/** An instructor's home department. */
	static final String DEPARTMENT_CLAIM = "department";

	@Bean
	public SecurityFilterChain apiSecurity(HttpSecurity http, ProfileSync profiles) throws Exception {
		http
			.csrf(csrf -> csrf.disable())
			// The API never uses a session, and never touches a session cookie another app on this host may have set.
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
				.sessionFixation(fixation -> fixation.none()))
			.addFilterAfter(new ProfileSyncFilter(profiles), BearerTokenAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
				// The static UI holds no data; it signs in first and sends the token with every API call.
				.requestMatchers(HttpMethod.GET, "/", "/index.html", "/signin-callback.html", "/css/**", "/js/**")
					.permitAll()
				// Public sign-in configuration: which flow the browser should run (dev token vs OIDC).
				.requestMatchers(HttpMethod.GET, "/auth/config").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())));
		return http.build();
	}

	private static JwtAuthenticationConverter roleConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authoritiesOf);
		return converter;
	}

	/** An unknown or missing role grants nothing, so every @PreAuthorize answers 403. */
	private static List<GrantedAuthority> authoritiesOf(Jwt jwt) {
		return roleOf(jwt).stream()
				.<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
				.toList();
	}

	static Optional<Role> roleOf(Jwt jwt) {
		String claim = jwt.getClaimAsString(ROLE_CLAIM);
		return Stream.of(Role.values()).filter(role -> role.name().equals(claim)).findFirst();
	}

}