package security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import service.NotFoundException;

/**
 * Stands in for the Core Platform sign-in. The caller picks an account and the role comes from that
 * account, because no endpoint ever accepts a role from the request (NFR1.4).
 */
@RestController
@Profile({ "local", "dev", "test" })
public class DevTokenController {

	private static final Duration LIFETIME = Duration.ofHours(8);

	private final JwtEncoder encoder;
	private final Clock clock;

	public DevTokenController(JwtEncoder encoder, Clock clock) {
		this.encoder = encoder;
		this.clock = clock;
	}

	@PostMapping("/dev/token")
	public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
		DevAccount account = DevAccount.byId(request.userId())
				.orElseThrow(() -> new NotFoundException("No test account has the id '" + request.userId() + "'."));
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(DevAuthConfig.ISSUER)
				.subject(account.id)
				.issuedAt(now)
				.expiresAt(now.plus(LIFETIME))
				.claim(SecurityConfig.NAME_CLAIM, account.displayName)
				.claim(SecurityConfig.ROLE_CLAIM, account.role.name())
				.build();
		String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
		return new TokenResponse(token, "Bearer", LIFETIME.toSeconds());
	}

	public record TokenRequest(@NotBlank(message = "User id is required") String userId) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

}
