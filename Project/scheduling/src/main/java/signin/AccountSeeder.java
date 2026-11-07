package signin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** On first start (no accounts yet), creates the seed accounts from configuration. Never touches existing ones. */
@Component
@Profile("local")
public class AccountSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

	private final AccountStore accounts;
	private final SignInProperties properties;

	public AccountSeeder(AccountStore accounts, SignInProperties properties) {
		this.accounts = accounts;
		this.properties = properties;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (accounts.count() > 0) {
			return;
		}
		properties.seedAccounts().forEach(seed -> accounts.create(seed.toAccount()));
		log.info("Created {} sign-in accounts.", properties.seedAccounts().size());
	}

}
