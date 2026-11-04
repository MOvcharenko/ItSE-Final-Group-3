package service;

import org.springframework.stereotype.Component;

import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * Bookings and import batches point at app_user with a foreign key, so the caller's profile row must exist before
 * they are saved. Normally Dev A creates it at sign-in; this makes the services safe if that has not happened yet.
 */
@Component
class UserProfiles {

	private final AppUserRepository users;

	UserProfiles(AppUserRepository users) {
		this.users = users;
	}

	AppUserEntity ensure(Caller caller) {
		return users.findById(caller.id())
				.orElseGet(() -> users.save(new AppUserEntity(caller.id(), caller.displayName(), null, null)));
	}
}
