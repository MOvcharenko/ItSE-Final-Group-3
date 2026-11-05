package integration;

/**
 * Thrown when the Student Information System (SIS) is temporarily unavailable.
 * Conflict detection degrades gracefully by skipping SIS-dependent rules (UC-06 3a).
 */
public class SisUnavailableException extends RuntimeException {

	public SisUnavailableException(String message) {
		super(message);
	}

	public SisUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
