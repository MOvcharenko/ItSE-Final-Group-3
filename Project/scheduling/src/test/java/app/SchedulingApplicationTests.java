package app;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import security.CurrentUserProvider;
import service.NoticeService;

@SpringBootTest
@ActiveProfiles("test")
class SchedulingApplicationTests {

	@Autowired
	Clock clock;

	@Autowired
	CurrentUserProvider currentUser;

	@Autowired
	NoticeService notices;

	@Test
	@DisplayName("Context loads with the cross-stream contracts wired")
	void contextLoads() {
		assertThat(currentUser.get()).isNotNull();
		assertThat(notices).isNotNull();
	}

	@Test
	@DisplayName("Clock uses the configured scheduling.time-zone")
	void clockUsesConfiguredZone() {
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
	}

}
