package domain;

/**
 * Student — read-only access to personal calendar and notices (TR3.2).
 */
public class Student extends User {

	private final String studentId;

	public Student(String userId, String displayName, String email, String studentId) {
		super(userId, displayName, email, Role.STUDENT);
		this.studentId = studentId;
	}

	public String studentId() { return studentId; }

}
