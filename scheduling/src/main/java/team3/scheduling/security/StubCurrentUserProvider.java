package team3.scheduling.security;

import java.util.Set;

import org.springframework.stereotype.Component;

import team3.scheduling.domain.Role;

/**
 * Day-1 stub so other streams can code against {@link CurrentUserProvider}.
 * TODO(Dev A, Day 2): replace with the JWT-backed implementation.
 */
@Component
public class StubCurrentUserProvider implements CurrentUserProvider {

	private static final CurrentUser JORDAN_ELLIS =
			new CurrentUser("jellis", "Jordan Ellis", Role.SCHEDULER, Set.of("ENG"));

	@Override
	public CurrentUser get() {
		return JORDAN_ELLIS;
	}

}
