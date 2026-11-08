package accounts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stands in for the Core Platform: accounts, passwords and sign-in, issuing the tokens the Academic Scheduling module
 * accepts. A separate app on purpose: the scheduling module only checks tokens and keeps no accounts of its own.
 */
@SpringBootApplication
public class AccountServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccountServiceApplication.class, args);
	}

}
