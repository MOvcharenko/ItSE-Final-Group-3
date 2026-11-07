package signin;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Looks accounts up for the sign-in form. The signed-in principal is named by the account's <em>id</em>, not the
 * username typed, so the token's {@code sub} is the id the module stores (and OIDC logout, which compares the ID
 * token's {@code sub} with the principal, keeps working). A disabled account cannot sign in.
 */
@Component
@Profile("local")
public class AccountDirectory implements UserDetailsService {

	static final String ADMIN = "ADMIN";

	private final AccountStore accounts;

	public AccountDirectory(AccountStore accounts) {
		this.accounts = accounts;
	}

	@Override
	public UserDetails loadUserByUsername(String username) {
		Account account = accounts.byUsername(username.trim())
				.orElseThrow(() -> new UsernameNotFoundException("No such account."));
		return User.withUsername(account.id())
				.password(account.password())
				.disabled(!account.enabled())
				.roles(account.admin() ? new String[] { ADMIN } : new String[0])
				.build();
	}

}
