package service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import domain.Role;
import security.CurrentUser;
import security.CurrentUserProvider;

/**
 * The people of TP3 as callers. If Dev A's CurrentUser looks different from what Caller assumes, adapt it here and
 * in Caller.java; the tests themselves do not touch it.
 */
final class TestUsers {

	static final CurrentUser MAYA = new CurrentUser("U01", "Maya Brooks", Role.SCHEDULER, Set.of("CS", "MATH"));
	static final CurrentUser MAYA_CS_ONLY = new CurrentUser("U01", "Maya Brooks", Role.SCHEDULER, Set.of("CS"));
	static final CurrentUser BIANCHI = new CurrentUser("U02", "L. Bianchi", Role.INSTRUCTOR, Set.of());
	static final CurrentUser STUDENT = new CurrentUser("U03", "Sam Student", Role.STUDENT, Set.of());

	private TestUsers() {
	}

	static Caller caller(CurrentUser who) {
		CurrentUserProvider provider = mock(CurrentUserProvider.class);
		when(provider.get()).thenReturn(who);
		return new Caller(provider);
	}
}
