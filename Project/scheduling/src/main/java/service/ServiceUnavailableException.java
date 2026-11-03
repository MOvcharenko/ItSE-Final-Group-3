package service;

/** Something this module depends on did not answer, and the request cannot be met without it. Mapped to 503. */
public class ServiceUnavailableException extends RuntimeException {

	public ServiceUnavailableException(String message) {
		super(message);
	}

}
