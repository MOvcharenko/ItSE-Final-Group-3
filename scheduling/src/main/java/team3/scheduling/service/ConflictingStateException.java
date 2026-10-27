package team3.scheduling.service;

/**
 * The request clashes with the current state of a record (e.g. approving a request
 * that is no longer pending). Mapped to 409.
 */
public class ConflictingStateException extends RuntimeException {

	public ConflictingStateException(String message) {
		super(message);
	}

}
