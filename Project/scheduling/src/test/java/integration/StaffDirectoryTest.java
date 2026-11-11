package integration;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import domain.Role;
import security.ProfileSync;

class StaffDirectoryTest {

	private static final String STAFF = "http://accounts.test/api/staff";
	private static final String TOKEN = "http://accounts.test/oauth2/token";
	private static final String BASIC = "Basic " + Base64.getEncoder()
			.encodeToString("scheduling-directory:secret".getBytes(StandardCharsets.UTF_8));

	private final ProfileSync profiles = mock(ProfileSync.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);
	private final RestClient.Builder http = RestClient.builder();
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();

	private StaffDirectory directory(String staffUri) {
		return new StaffDirectory(profiles, http, clock, staffUri, TOKEN, "scheduling-directory", "secret");
	}

	private void expectToken() {
		server.expect(once(), requestTo(TOKEN))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", BASIC))
				.andExpect(content().formDataContains(Map.of("grant_type", "client_credentials")))
				.andRespond(withSuccess("{\"access_token\":\"t1\",\"token_type\":\"Bearer\",\"expires_in\":300}",
						MediaType.APPLICATION_JSON));
	}

	private void expectStaff() {
		server.expect(requestTo(STAFF))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Authorization", "Bearer t1"))
				.andRespond(withSuccess("""
						[{"id":"T20","name":"Pat New","email":"pat@univ.edu","role":"INSTRUCTOR","department":"PHYS","departments":[]},
						 {"id":"U01","name":"Maya Brooks","email":null,"role":"SCHEDULER","department":null,"departments":["CS","MATH"]}]
						""", MediaType.APPLICATION_JSON));
	}

	@Test
	@DisplayName("A new account in the Core Platform reaches the profiles before that person has signed in")
	void copiesTheStaffList() {
		expectToken();
		expectStaff();

		directory(STAFF).refreshProfiles();

		server.verify();
		verify(profiles).sync(List.of(
				new ProfileSync.Staff("T20", "Pat New", "pat@univ.edu", Role.INSTRUCTOR, "PHYS", List.of()),
				new ProfileSync.Staff("U01", "Maya Brooks", null, Role.SCHEDULER, null, List.of("CS", "MATH"))));
	}

	@Test
	@DisplayName("The client's token is reused until it is about to expire")
	void reusesTheToken() {
		expectToken();
		expectStaff();
		expectStaff();
		StaffDirectory directory = directory(STAFF);

		directory.refreshProfiles();
		directory.refreshProfiles();

		server.verify();
		verify(profiles, times(2)).sync(anyList());
	}

	@Test
	@DisplayName("If the Core Platform does not answer, the stored profiles are used and nothing is thrown")
	void coreDown() {
		expectToken();
		server.expect(requestTo(STAFF)).andRespond(withServiceUnavailable());

		directory(STAFF).refreshProfiles();

		verify(profiles, never()).sync(anyList());
	}

	@Test
	@DisplayName("Without scheduling.directory.uri (dev, test, local) nothing is called")
	void offWhenNotConfigured() {
		directory("").refreshProfiles();

		server.verify();
		verify(profiles, never()).sync(anyList());
	}

}
