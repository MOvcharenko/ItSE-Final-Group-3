package signin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.ActiveProfiles;

import app.SchedulingApplication;
import security.AuthConfigController;

/**
 * The local sign-in follows how the app is configured: the port it runs on, and the client id and scope the browser
 * is given, so changing a setting never leaves the two disagreeing.
 */
@SpringBootTest(classes = SchedulingApplication.class, properties = {
		"server.port=8081",
		"scheduling.auth.client-id=campus-timetable",
		"scheduling.auth.scope=openid profile",
		"spring.datasource.url=jdbc:h2:mem:local-signin-port;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
		"scheduling.signin.signing-key-file=${java.io.tmpdir}/scheduling-signin-test-${random.uuid}/signing-key.json" })
@ActiveProfiles("local")
class LocalSignInPortIT {

	@Autowired
	RegisteredClientRepository clients;

	@Autowired
	AuthConfigController authConfig;

	@Test
	@DisplayName("Started on port 8081, sign-in may return to 8081, and no longer to 8080")
	void redirectsFollowThePort() {
		RegisteredClient client = clients.findByClientId("campus-timetable");
		assertThat(client.getRedirectUris()).containsExactlyInAnyOrder(
				"http://localhost:8081/signin-callback.html", "http://127.0.0.1:8081/signin-callback.html");
		assertThat(client.getPostLogoutRedirectUris()).containsExactlyInAnyOrder(
				"http://localhost:8081/", "http://127.0.0.1:8081/");
	}

	@Test
	@DisplayName("A configured client id and scope reach both the browser and the local sign-in's registration")
	void clientIdAndScopeFollowTheSettings() {
		assertThat(authConfig.config()).containsEntry("clientId", "campus-timetable").containsEntry("scope", "openid profile");
		RegisteredClient client = clients.findByClientId("campus-timetable");
		assertThat(client).isNotNull();
		assertThat(client.getScopes()).containsExactlyInAnyOrder("openid", "profile");
		assertThat(clients.findByClientId("scheduling-web")).isNull();
	}

}
