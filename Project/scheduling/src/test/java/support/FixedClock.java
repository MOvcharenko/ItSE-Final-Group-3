package support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Pinned clocks for tests. The test plan dates (Oct 14 and Oct 20, 2026) are in the past,
 * so tests must never depend on the real date.
 */
public final class FixedClock {

	public static final ZoneId ZONE = ZoneId.of("America/New_York");

	private FixedClock() {
	}

	/** Wed 14 Oct 2026, 09:00. */
	public static Clock oct14() {
		return at(LocalDateTime.of(2026, 10, 14, 9, 0));
	}

	/** Tue 20 Oct 2026, 09:00. */
	public static Clock oct20() {
		return at(LocalDateTime.of(2026, 10, 20, 9, 0));
	}

	public static Clock at(LocalDateTime time) {
		return Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
	}

}
