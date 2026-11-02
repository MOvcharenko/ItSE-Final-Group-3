package security;

import java.util.Optional;
import java.util.stream.Stream;

import domain.Role;

/** The three TP3 test accounts, with the same ids as the Phase 2 domain test. */
enum DevAccount {

	MAYA_BROOKS("U01", "Maya Brooks", Role.SCHEDULER),
	L_BIANCHI("U02", "L. Bianchi", Role.INSTRUCTOR),
	JORDAN_ELLIS("U03", "Jordan Ellis", Role.STUDENT);

	final String id;
	final String displayName;
	final Role role;

	DevAccount(String id, String displayName, Role role) {
		this.id = id;
		this.displayName = displayName;
		this.role = role;
	}

	static Optional<DevAccount> byId(String id) {
		return Stream.of(values()).filter(account -> account.id.equals(id)).findFirst();
	}

}
