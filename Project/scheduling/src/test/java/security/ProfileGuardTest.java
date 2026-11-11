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
import org.springframework.web.server.ResponseStatusException;

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

	/** As a command-line argument, so it wins over the profile's own file database in ./data. */
	private static String inMemoryDatabase(String name) {
		return "--spring.datasource.url=jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
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
			// the browser is told why it cannot sign in, rather than sent to a dead address
			assertThatThrownBy(() -> context.getBean(AuthConfigController.class).config())
				.isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot be reached");
		}
	}

	@Test
	@DisplayName("The local profile (the default for spring-boot:run) is the test-account picker, with no account management")
	void localProfileIsThePicker() {
		try (ConfigurableApplicationContext context = defaultProfileApp().profiles("local").run(inMemoryDatabase("guard-local"))) {
			assertThat(context.getBeansOfType(DevTokenController.class)).hasSize(1);
			assertThat(context.getBean(AuthConfigController.class).config()).containsEntry("mode", "dev");
			assertThat(context.containsBean("accountController")).isFalse();
		}
	}

	@Test
	@DisplayName("The accounts profile trusts Project/account-service as its Core Platform, and has no dev login")
	void accountsProfileTrustsTheAccountService() {
		// The profile's own port (8081) would win over defaultProfileApp's server.port=0, so it is given here too.
		try (ConfigurableApplicationContext context = defaultProfileApp().profiles("accounts")
				.run(inMemoryDatabase("guard-accounts"), "--server.port=0")) {
			assertThat(context.getEnvironment().getProperty("scheduling.directory.uri"))
				.isEqualTo("http://localhost:9000/api/staff");
			assertThat(context.getEnvironment().getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
				.isEqualTo("http://localhost:9000");
			assertThat(context.getBeansOfType(JwtDecoder.class)).hasSize(1);
			assertThat(context.getBeansOfType(DevTokenController.class)).isEmpty();
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
