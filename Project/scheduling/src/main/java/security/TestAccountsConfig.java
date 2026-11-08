package security;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import domain.Role;
import persistence.AppUserEntity;
import persistence.AppUserRepository;

/**
 * Profiles for the TP3 staff accounts, so a Scheduler can assign Bianchi before she has ever signed in. They sign in
 * through /dev/token, or in the accounts profile through Project/account-service, which has the same ids. Anyone else
 * gets a profile on their first request ({@link ProfileSyncFilter}). Students are not stored here: the SIS owns them
 * (TR1.3).
 */
@Configuration
@Profile({ "local", "dev", "test", "accounts" })
public class TestAccountsConfig {

	@Bean
	public ApplicationRunner devAccountsLoader(AppUserRepository users) {
		return args -> {
			for (DevAccount account : DevAccount.values()) {
				if (account.role == Role.STUDENT || users.existsById(account.id)) {
					continue;
				}
				AppUserEntity profile = new AppUserEntity(account.id, account.displayName, account.email, account.department);
				account.managedDepartments.forEach(profile::authorizeDepartment);
				users.save(profile);
			}
		};
	}

}
