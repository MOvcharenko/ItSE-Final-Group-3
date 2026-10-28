package service;

/**
 * Thrown when an approval would overlap an already-approved booking (NFR3.1, UC-08 4a).
 * Extends ConflictingStateException so ApiExceptionHandler already answers it with HTTP 409.
 */
public class BookingCollisionException extends ConflictingStateException {

	public BookingCollisionException(String message) {
		super(message);
	}
}
