package security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class IssuerDiscoveryTest {

	private static final String DOCUMENT = "http://idp.example/realms/campus/.well-known/openid-configuration";

	private final RestClient.Builder http = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

	@Test
	@DisplayName("The browser gets the sign-in addresses the Core Platform publishes, whatever product runs it; read once")
	void readsTheDiscoveryDocument() {
		server.expect(ExpectedCount.once(), requestTo(DOCUMENT)).andRespond(withSuccess("""
				{"issuer": "http://idp.example/realms/campus",
				 "authorization_endpoint": "http://idp.example/realms/campus/protocol/openid-connect/auth",
				 "token_endpoint": "http://idp.example/realms/campus/protocol/openid-connect/token",
				 "end_session_endpoint": "http://idp.example/realms/campus/protocol/openid-connect/logout"}
				""", MediaType.APPLICATION_JSON));
		IssuerDiscovery discovery = new IssuerDiscovery(http, "http://idp.example/realms/campus/");

		IssuerDiscovery.Endpoints endpoints = discovery.endpoints();
		discovery.endpoints();

		assertThat(endpoints.authorization()).isEqualTo("http://idp.example/realms/campus/protocol/openid-connect/auth");
		assertThat(endpoints.token()).isEqualTo("http://idp.example/realms/campus/protocol/openid-connect/token");
		assertThat(endpoints.endSession()).isEqualTo("http://idp.example/realms/campus/protocol/openid-connect/logout");
		server.verify();
	}

	@Test
	@DisplayName("A Core Platform without a sign-out endpoint leaves it blank")
	void noEndSession() {
		server.expect(requestTo(DOCUMENT)).andRespond(withSuccess("""
				{"authorization_endpoint": "http://idp.example/a", "token_endpoint": "http://idp.example/t"}
				""", MediaType.APPLICATION_JSON));

		assertThat(new IssuerDiscovery(http, "http://idp.example/realms/campus").endpoints().endSession()).isEmpty();
	}

	@Test
	@DisplayName("A Core Platform that cannot be reached answers 503 with a plain reason, and is asked again next time")
	void unreachable() {
		server.expect(ExpectedCount.twice(), requestTo(DOCUMENT)).andRespond(withServerError());
		IssuerDiscovery discovery = new IssuerDiscovery(http, "http://idp.example/realms/campus");

		for (int attempt = 0; attempt < 2; attempt++) {
			assertThatThrownBy(discovery::endpoints)
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
				.hasMessageContaining("cannot be reached");
		}
		server.verify();
	}

	@Test
	@DisplayName("With no issuer configured there is nothing to sign in with")
	void noIssuer() {
		assertThatThrownBy(new IssuerDiscovery(http, "")::endpoints).isInstanceOf(ResponseStatusException.class);
	}

}
