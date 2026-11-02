package security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Outside dev and test, Spring Boot builds the token decoder from the issuer URI. This only makes a missing
 * URI fail with a clear message.
 */
@Configuration
@Profile("!dev & !test")
public class IssuerRequiredConfig {

	public IssuerRequiredConfig(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
		if (issuer.isBlank()) {
			throw new IllegalStateException("Set spring.security.oauth2.resourceserver.jwt.issuer-uri to the "
					+ "Core Platform issuer, or run with the dev profile for local work.");
		}
	}

}
