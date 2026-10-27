package service;

/** The requested record does not exist. Mapped to 404. */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

	public static NotFoundException of(String what, Object id) {
		return new NotFoundException(what + " " + id + " was not found.");
	}

}
