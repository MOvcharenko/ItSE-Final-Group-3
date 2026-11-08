package accounts;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account changes: password change, and the administrator's create, reset and enable/disable. */
@Service
public class AccountService {

	static final int MIN_PASSWORD_LENGTH = 10;
	/** The longest name the scheduling module's profiles (app_user.display_name) hold. */
	static final int MAX_NAME_LENGTH = 150;
	/** The longest department the scheduling module's profiles (app_user.department) hold. */
	static final int MAX_DEPARTMENT_LENGTH = 100;

	private final AccountStore accounts;
	private final PasswordEncoder encoder;

	public AccountService(AccountStore accounts, PasswordEncoder encoder) {
		this.accounts = accounts;
		this.encoder = encoder;
	}

	/** A plain-language reason the change was refused. */
	public static class RejectedException extends RuntimeException {

		public RejectedException(String message) {
			super(message);
		}

	}

	@Transactional
	public void changeOwnPassword(String accountId, String current, String replacement, String confirmation) {
		Account account = accounts.byId(accountId).orElseThrow(() -> new RejectedException("Your account no longer exists."));
		if (current == null || !encoder.matches(current, account.password())) {
			throw new RejectedException("Your current password is not correct.");
		}
		if (replacement != null && encoder.matches(replacement, account.password())) {
			throw new RejectedException("Choose a password different from your current one.");
		}
		requireAcceptable(replacement, confirmation);
		accounts.updatePassword(accountId, encoder.encode(replacement));
	}

	@Transactional
	public void create(String id, String username, String name, String email, String role, boolean admin,
			String departments, String homeDepartment, String password, String confirmation) {
		id = trim(id);
		username = trim(username);
		name = trim(name);
		role = trim(role);
		if (id.isEmpty() || username.isEmpty() || name.isEmpty()) {
			throw new RejectedException("Id, username and name are required.");
		}
		if (name.length() > MAX_NAME_LENGTH) {
			throw new RejectedException("The name may be at most " + MAX_NAME_LENGTH + " characters.");
		}
		if (!id.matches("[A-Za-z0-9._-]{1,100}")) {
			throw new RejectedException("The id may use letters, digits, dots, dashes and underscores only.");
		}
		// Every account needs a role: without one, Academic Scheduling has nothing to show it.
		Role accountRole = roleNamed(role);
		if (accounts.idTaken(id)) {
			throw new RejectedException("Another account already has the id " + id + ".");
		}
		if (accounts.usernameTaken(username)) {
			throw new RejectedException("Another account already has the username " + username + ".");
		}
		requireAcceptable(password, confirmation);
		List<String> departmentList = accountRole == Role.SCHEDULER
				? Arrays.stream(trim(departments).split("[,\\s]+")).filter(d -> !d.isBlank()).map(String::toUpperCase).distinct().toList()
				: List.of();
		// Only an instructor has a home department, as only a scheduler manages departments.
		String home = accountRole == Role.INSTRUCTOR && !trim(homeDepartment).isEmpty()
				? trim(homeDepartment).toUpperCase(Locale.ROOT) : null;
		if (home != null && home.length() > MAX_DEPARTMENT_LENGTH) {
			throw new RejectedException("The home department may be at most " + MAX_DEPARTMENT_LENGTH + " characters.");
		}
		accounts.create(new Account(id, username, encoder.encode(password), name, trim(email).isEmpty() ? null : trim(email),
				accountRole, admin, true, departmentList, home));
	}

	private static Role roleNamed(String role) {
		return Arrays.stream(Role.values()).filter(r -> r.name().equals(role)).findFirst()
				.orElseThrow(() -> new RejectedException("Choose a role of " + Arrays.stream(Role.values())
						.map(r -> r.name().charAt(0) + r.name().substring(1).toLowerCase(Locale.ROOT))
						.collect(Collectors.joining(", ")) + "."));
	}

	@Transactional
	public void resetPassword(String accountId, String password, String confirmation) {
		accounts.byId(accountId).orElseThrow(() -> new RejectedException("No such account."));
		requireAcceptable(password, confirmation);
		accounts.updatePassword(accountId, encoder.encode(password));
	}

	@Transactional
	public void setEnabled(String actingAdminId, String accountId, boolean enabled) {
		accounts.byId(accountId).orElseThrow(() -> new RejectedException("No such account."));
		if (!enabled && accountId.equals(actingAdminId)) {
			throw new RejectedException("You cannot disable your own account.");
		}
		accounts.setEnabled(accountId, enabled);
	}

	private static void requireAcceptable(String password, String confirmation) {
		if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
			throw new RejectedException("The new password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
		}
		if (!password.equals(confirmation)) {
			throw new RejectedException("The two new passwords do not match.");
		}
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

}
