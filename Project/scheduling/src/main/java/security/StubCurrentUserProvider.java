package security;

import java.util.Set;

import org.springframework.stereotype.Component;

import domain.Role;

/**
 * Day-1 stub so other streams can code against {@link CurrentUserProvider}.
 * TODO(Dev A): replace with the JWT-backed implementation.
 */
@Component
public class StubCurrentUserProvider implements CurrentUserProvider {

	/** TP3 scheduler account, as in the Phase 2 domain test. */
	private static final CurrentUser MAYA_BROOKS =
			new CurrentUser("U01", "Maya Brooks", Role.SCHEDULER, Set.of("CS", "MATH"));

	@Override
	public CurrentUser get() {
		return MAYA_BROOKS;
	}

}
