package team3.scheduling.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Services inject {@link Clock} so tests can pin the date
 * (the test plan uses Oct 14 and Oct 20, 2026).
 */
@Configuration
public class TimeConfig {

	@Bean
	public Clock clock(@Value("${scheduling.time-zone:}") String timeZone) {
		ZoneId zone = timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
		return Clock.system(zone);
	}

}
