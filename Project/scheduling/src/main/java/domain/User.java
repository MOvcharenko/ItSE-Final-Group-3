package domain;

/**
 * User — abstract base actor resolved from the Core Platform JWT (TR3.1, TR4.2).
 */
public abstract class User {

	private final String userId;
	private final String displayName;
	private final String email;
	private final Role role;

	protected User(String userId, String displayName, String email, Role role) {
		this.userId = userId;
		this.displayName = displayName;
		this.email = email;
		this.role = role;
	}

	public String userId() { return userId; }
	public String displayName() { return displayName; }
	public String email() { return email; }
	public Role role() { return role; }

}
