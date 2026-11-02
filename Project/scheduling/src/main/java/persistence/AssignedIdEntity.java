package persistence;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

/**
 * Base class for entities whose id is assigned by the application (UUID text, or a readable id in fixtures,
 * or the Core Platform user id). Implementing {@link Persistable} tells Spring Data a freshly built entity is new,
 * so {@code save()} inserts straight away instead of doing a SELECT first (the default for a non-null id).
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<String> {

	@Id
	@Column(name = "id", nullable = false)
	private String id;

	@Transient
	private boolean newEntity = true;

	protected AssignedIdEntity() {
	}

	protected AssignedIdEntity(String id) {
		this.id = id;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	@PostLoad
	@PostPersist
	void markPersisted() {
		this.newEntity = false;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		// getId(), not the field: a lazy Hibernate proxy has an empty field
		return o instanceof AssignedIdEntity other && id != null && id.equals(other.getId());
	}

	@Override
	public int hashCode() {
		return id == null ? 0 : id.hashCode();
	}
}
