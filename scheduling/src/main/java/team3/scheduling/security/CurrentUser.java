package team3.scheduling.security;

import java.util.Set;

import team3.scheduling.domain.Role;

/**
 * The signed-in user as taken from the JWT: id, role and the departments a scheduler
 * may manage. Instructor profiles are looked up by {@code id}.
 */
public record CurrentUser(String id, String displayName, Role role, Set<String> departments) {

	public CurrentUser {
		departments = Set.copyOf(departments);
	}

	public boolean isScheduler() {
		return role == Role.SCHEDULER;
	}

	/** Department check used by services (NFR1.3, TR3.4); never done in the UI. */
	public boolean canManage(String department) {
		return isScheduler() && departments.contains(department);
	}

}
