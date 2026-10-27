package service;

/**
 * The request is well-formed but breaks a business rule (e.g. editing availability
 * after the term is published). Mapped to 422.
 */
public class BusinessRuleException extends RuntimeException {

	public BusinessRuleException(String message) {
		super(message);
	}

}
