package service;

import java.util.Locale;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import domain.Role;
import security.CurrentUser;
import security.CurrentUserProvider;

/**
 * Who is calling, as the Catalog, Booking and Import services need to know it. The ONLY class in this stream that
 * touches security.CurrentUserProvider: if Dev A's API looks different from what is assumed in user() below, this is
 * the one file to adapt. Role and departments always come from the provider (the token), never from a request (NFR1.4).
 * Every service method re-checks the role itself, so the @PreAuthorize on the controllers is not the only guard (NFR1.3).
 */
@Component
class Caller {

	private final CurrentUserProvider provider;

	Caller(CurrentUserProvider provider) {
		this.provider = provider;
	}

	// ---- the single point that touches Dev A's provider: CurrentUserProvider.get() returns a CurrentUser record
	// ---- (id, displayName, role, departments) with isScheduler() and canManage(department) ----
	private CurrentUser user() {
		return provider.get();
	}

	String id() {
		return user().id();
	}

	String displayName() {
		return user().displayName();
	}

	boolean isScheduler() {
		return user().isScheduler();
	}

	boolean isInstructor() {
		return user().role() == Role.INSTRUCTOR;
	}

	/** Departments this Scheduler may change (TR3.4). */
	Set<String> departments() {
		return user().departments();
	}

	/** The department rule itself belongs to Dev A's CurrentUser (NFR1.3, TR3.4); it compares exactly. */
	boolean mayEdit(String department) {
		return user().canManage(department);
	}

	/**
	 * What the user typed, in the spelling the Scheduler's authorization uses ("math" becomes "MATH"), so the check is
	 * not defeated by capitals and the database holds one spelling per department. Unknown names come back trimmed
	 * and fail mayEdit().
	 */
	String canonicalDepartment(String typed) {
		String t = typed.trim();
		return departments().stream().filter(d -> d.equalsIgnoreCase(t)).findFirst().orElse(t);
	}

	void requireScheduler() {
		if (!isScheduler()) {
			throw new AccessDeniedException("Only a Scheduler can do this.");
		}
	}

	void requireSchedulerOrInstructor() {
		if (!isScheduler() && !isInstructor()) {
			throw new AccessDeniedException("Only Schedulers and Instructors can do this.");
		}
	}

	@Override
	public String toString() {
		return "Caller[" + id().toLowerCase(Locale.ROOT) + "]";
	}
}
