package security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Outside dev, test and local, Spring Boot builds the token decoder from the issuer URI and checks the audiences
 * given. This only makes a missing setting fail at startup with a clear message, rather than as a 401 on every call.
 * The audience has no default because it is whatever the Core Platform puts in its tokens for this API; a Core
 * Platform whose tokens carry no audience can turn the check off explicitly.
 */
@Configuration
@Profile("!dev & !test & !local")
public class IssuerRequiredConfig {

	public IssuerRequiredConfig(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer,
			@Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String audiences,
			@Value("${scheduling.auth.require-audience:true}") boolean requireAudience) {
		if (issuer.isBlank()) {
			throw new IllegalStateException("Set spring.security.oauth2.resourceserver.jwt.issuer-uri to the "
					+ "Core Platform issuer, or run with the dev profile for local work.");
		}
		if (requireAudience && audiences.isBlank()) {
			throw new IllegalStateException("Set spring.security.oauth2.resourceserver.jwt.audiences to the aud the "
					+ "Core Platform puts in its access tokens for this API, so tokens issued for other services are "
					+ "refused. If its tokens carry no aud at all, set scheduling.auth.require-audience=false instead.");
		}
	}

}
