package accounts;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff list the scheduling server reads, so an instructor or scheduler created here can be assigned to classes
 * before they have ever signed in. Only enabled staff: students belong to the SIS, and a disabled account is not
 * offered for new work. The fields are the same as the token claims the scheduling module reads at sign-in.
 */
@RestController
public class StaffController {

	private final AccountStore accounts;

	public StaffController(AccountStore accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/api/staff")
	public List<StaffMember> staff() {
		return accounts.all().stream()
				.filter(account -> account.enabled() && account.role() != Role.STUDENT)
				.map(account -> new StaffMember(account.id(), account.name(), account.email(), account.role(),
						account.homeDepartment(), account.departments()))
				.toList();
	}

	/**
	 * @param department  an instructor's home department (the token's {@code department} claim)
	 * @param departments the departments a scheduler manages (the token's {@code departments} claim)
	 */
	public record StaffMember(String id, String name, String email, Role role, String department,
			List<String> departments) {
	}

}
