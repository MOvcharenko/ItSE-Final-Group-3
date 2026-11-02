package persistence;

import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Profile of a Core Platform user (table app_user). Holds NO role and NO password: role comes from the token on
 * every request (NFR1.4). SHARED by all three streams: if another stream already added this class, keep one copy.
 */
@Entity
@Table(name = "app_user")
public class AppUserEntity extends AssignedIdEntity {

	@Column(name = "display_name", nullable = false, length = 150)
	private String displayName;

	@Column(name = "email", length = 200)
	private String email;

	@Column(name = "department", length = 100)
	private String department;

	/** Scheduler.authorizedDepartments (TR3.4). Read it with AppUserRepository.findAuthorizedDepartments. */
	@ElementCollection
	@CollectionTable(name = "scheduler_department", joinColumns = @JoinColumn(name = "scheduler_id"))
	@Column(name = "department", nullable = false, length = 100)
	private Set<String> authorizedDepartments = new LinkedHashSet<>();

	protected AppUserEntity() {
	}

	public AppUserEntity(String id, String displayName, String email, String department) {
		super(id);
		this.displayName = displayName;
		this.email = email;
		this.department = department;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getEmail() {
		return email;
	}

	public String getDepartment() {
		return department;
	}

	public void authorizeDepartment(String authorizedDepartment) {
		authorizedDepartments.add(authorizedDepartment);
	}
}
