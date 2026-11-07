package security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import app.SchedulingApplication;
import service.BookingService;
import service.CatalogService;
import service.ImportService;

/**
 * Starts the real application in the default profile: the dev login must never reach a real deployment,
 * and a deployment must not start without an issuer.
 */
class ProfileGuardTest {

	private static SpringApplicationBuilder defaultProfileApp() {
		return new SpringApplicationBuilder(SchedulingApplication.class, MockedServices.class).properties(
				"server.port=0",
				"spring.datasource.url=jdbc:h2:mem:guard;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
	}

	@Test
	@DisplayName("The default profile refuses to start without an issuer URI, and says why")
	void noIssuerNoStart() {
		assertThatThrownBy(() -> defaultProfileApp().run())
			.rootCause().hasMessageContaining("issuer-uri");
	}

	@Test
	@DisplayName("With an issuer but no audience, it refuses to start and says how to fix it")
	void noAudienceNoStart() {
		assertThatThrownBy(() -> defaultProfileApp()
			.properties("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/issuer")
			.run())
			.rootCause().hasMessageContaining("audiences").hasMessageContaining("scheduling.auth.require-audience=false");
	}

	@Test
	@DisplayName("With an issuer URI and an audience, Boot supplies the decoder, the issuer is not contacted at startup, and there is no dev login")
	void issuerConfigured() {
		try (ConfigurableApplicationContext context = defaultProfileApp()
			.properties("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/issuer",
					"spring.security.oauth2.resourceserver.jwt.audiences=scheduling-api")
			.run()) {
			assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
			assertThat(context.getBeansOfType(DevTokenController.class)).isEmpty();
			assertThat(context.getBeansOfType(DevAuthConfig.class)).isEmpty();
		}
	}

	@Test
	@DisplayName("A Core Platform whose tokens carry no audience can turn the check off explicitly")
	void audienceCheckTurnedOff() {
		try (ConfigurableApplicationContext context = defaultProfileApp()
			.properties("spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/issuer",
					"scheduling.auth.require-audience=false")
			.run()) {
			assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
		}
	}

	@Configuration
	static class MockedServices {

		@Bean
		@Primary
		CatalogService catalogService() {
			return mock(CatalogService.class);
		}

		@Bean
		@Primary
		BookingService bookingService() {
			return mock(BookingService.class);
		}

		@Bean
		@Primary
		ImportService importService() {
			return mock(ImportService.class);
		}

	}

}
