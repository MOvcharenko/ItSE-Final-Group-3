package security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/**
 * Plays the Core Platform locally (TP3): signs tokens with a key generated at startup, so no secret is
 * committed and tokens stop working after a restart. Never active outside the dev and test profiles.
 */
@Configuration
@Profile({ "dev", "test" })
public class DevAuthConfig {

	static final String ISSUER = "https://core-platform.dev.local";

	@Bean
	public KeyPair devSigningKeys() throws NoSuchAlgorithmException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	/** Checks expiry with the same Clock that stamps the tokens, so tests can pin the date. */
	@Bean
	public JwtDecoder jwtDecoder(KeyPair devSigningKeys, Clock clock) {
		JwtTimestampValidator timestamps = new JwtTimestampValidator();
		timestamps.setClock(clock);
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) devSigningKeys.getPublic()).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(ISSUER)));
		return decoder;
	}

	@Bean
	public JwtEncoder jwtEncoder(KeyPair devSigningKeys) {
		RSAKey key = new RSAKey.Builder((RSAPublicKey) devSigningKeys.getPublic())
				.privateKey((RSAPrivateKey) devSigningKeys.getPrivate())
				.build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
	}

	/** Ordered ahead of the API chain. The H2 console cannot send a bearer token and runs in a frame. */
	@Bean
	@Order(0)
	public SecurityFilterChain devToolsSecurity(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/dev/token", "/h2-console/**")
			.csrf(csrf -> csrf.disable())
			.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

}
