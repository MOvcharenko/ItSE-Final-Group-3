package accounts;

import java.util.List;

/**
 * One account, as stored.
 *
 * @param id          the stable user id; it becomes the token's {@code sub}, which the scheduling module keys on
 * @param username    what the person types on the sign-in page
 * @param password    the encoded password, e.g. {@code {bcrypt}$2a$10$...}; never plaintext
 * @param role        the scheduling role, put in the token's {@code role} claim
 * @param admin       may manage accounts (change passwords, create and disable accounts)
 * @param departments departments a scheduler manages, put in the token's {@code departments} claim
 * @param homeDepartment an instructor's home department, put in the token's {@code department} claim
 */
public record Account(String id, String username, String password, String name, String email, Role role,
		boolean admin, boolean enabled, List<String> departments, String homeDepartment) {

	public Account {
		departments = departments == null ? List.of() : List.copyOf(departments);
	}

}
