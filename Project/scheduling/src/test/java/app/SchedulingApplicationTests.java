package app;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import security.CurrentUserProvider;
import service.BookingService;
import service.CatalogService;
import service.ImportService;
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

	// TODO(Dev C): remove these three once the service implementations are on main.
	@MockitoBean
	CatalogService catalog;

	@MockitoBean
	BookingService bookings;

	@MockitoBean
	ImportService imports;

	@Test
	@DisplayName("Context loads with the cross-stream contracts wired")
	void contextLoads() {
		assertThat(currentUser).isNotNull();
		assertThat(notices).isNotNull();
	}

	@Test
	@DisplayName("Clock uses the configured scheduling.time-zone")
	void clockUsesConfiguredZone() {
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
	}

}
