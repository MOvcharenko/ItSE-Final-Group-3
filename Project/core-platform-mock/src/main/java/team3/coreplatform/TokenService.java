package main.java.team3.coreplatform;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import jakarta.annotation.PostConstruct;

/**
 * Signs the access tokens the scheduling module will validate. The key pair is
 * generated once at startup, so restarting the mock invalidates any token it
 * issued before — the same behaviour as the scheduling module's own
 * /dev/token endpoint.
 */
@Service
public class TokenService {

	private final String issuer;
	private final String audience;
	private final Duration ttl;
	private RSAKey signingKey;

	public TokenService(
			@Value("${mock.issuer}") String issuer,
			@Value("${mock.audience}") String audience,
			@Value("${mock.token-ttl:PT8H}") Duration ttl) {
		this.issuer = issuer;
		this.audience = audience;
		this.ttl = ttl;
	}

	@PostConstruct
	void generateKey() throws JOSEException {
		this.signingKey = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
	}

	public JWKSet publicJwks() {
		return new JWKSet(signingKey.toPublicJWK());
	}

	public Duration ttl() {
		return ttl;
	}

	public String issue(Account account) throws JOSEException {
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(account.id())
				.issuer(issuer)
				.audience(audience)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(ttl)))
				.jwtID(UUID.randomUUID().toString())
				.claim("name", account.name())
				.claim("email", account.email())
				.claim("role", account.role())
				.claim("departments", account.departments())
				.build();
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
				.type(JOSEObjectType.JWT)
				.keyID(signingKey.getKeyID())
				.build();
		SignedJWT jwt = new SignedJWT(header, claims);
		jwt.sign(new RSASSASigner(signingKey));
		return jwt.serialize();
	}

}