package domain;

import java.util.List;

/**
 * Scheduler — staff actor with timetable and catalog management rights,
 * limited to the departments they are authorized for (TR3.4).
 */
public class Scheduler extends User {

	private final List<String> authorizedDepartments;

	public Scheduler(String userId, String displayName, String email, List<String> authorizedDepartments) {
		super(userId, displayName, email, Role.SCHEDULER);
		this.authorizedDepartments = List.copyOf(authorizedDepartments);
	}

	public List<String> authorizedDepartments() { return authorizedDepartments; }

	public boolean isAuthorizedFor(String department) {
		return authorizedDepartments.contains(department);
	}

}
