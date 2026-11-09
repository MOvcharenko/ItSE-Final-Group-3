package config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * Seeds the three TP3 test accounts on the local or shared database the first
 * time it is empty. Idempotent: skipped when U01 already exists, so restarting
 * the shared database does not duplicate or overwrite anything. Production is
 * never seeded: this bean is not in any production profile.
 *
 * <p>U01 is a Scheduler with CS + MATH; U02 is an Instructor in MATH; U03 is a
 * Student (no role column is stored — role comes from the token; U03 is
 * created so the dev picker does not race on the very first request).
 */
@Component
@Profile({ "local", "shared" })
class DevSeed implements ApplicationRunner {

	private final AppUserRepository users;

	DevSeed(AppUserRepository users) {
		this.users = users;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.existsById("U01")) {
			return;
		}
		AppUserEntity maya = new AppUserEntity("U01", "Maya Brooks", "m.brooks@univ.edu", null);
		maya.authorizeDepartment("CS");
		maya.authorizeDepartment("MATH");
		users.save(maya);
		users.save(new AppUserEntity("U02", "L. Bianchi", "l.bianchi@univ.edu", "MATH"));
		users.save(new AppUserEntity("U03", "Jordan Ellis", "j.ellis@univ.edu", null));
	}

}