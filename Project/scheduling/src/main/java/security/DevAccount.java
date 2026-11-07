package security;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import domain.Role;

/**
 * The three TP3 test accounts, with the same ids as the Phase 2 domain test. The only list of them: /dev/token signs
 * them in, {@link TestAccountsConfig} stores the staff profiles, and /auth/config gives the browser its picker.
 */
enum DevAccount {

	MAYA_BROOKS("U01", "Maya Brooks", Role.SCHEDULER, "m.brooks@univ.edu", null, Set.of("CS", "MATH")),
	L_BIANCHI("U02", "L. Bianchi", Role.INSTRUCTOR, "l.bianchi@univ.edu", "MATH", Set.of()),
	JORDAN_ELLIS("U03", "Jordan Ellis", Role.STUDENT, null, null, Set.of());

	final String id;
	final String displayName;
	final Role role;
	final String email;
	/** An instructor's home department. */
	final String department;
	/** The departments a scheduler manages (TR3.4). */
	final Set<String> managedDepartments;

	DevAccount(String id, String displayName, Role role, String email, String department, Set<String> managedDepartments) {
		this.id = id;
		this.displayName = displayName;
		this.role = role;
		this.email = email;
		this.department = department;
		this.managedDepartments = managedDepartments;
	}

	static Optional<DevAccount> byId(String id) {
		return Stream.of(values()).filter(account -> account.id.equals(id)).findFirst();
	}

}
