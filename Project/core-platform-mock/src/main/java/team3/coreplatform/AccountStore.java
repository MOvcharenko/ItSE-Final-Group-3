package main.java.team3.coreplatform;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** The three test accounts from the doc (TD-1 / TP3). Fixed on purpose. */
@Component
public class AccountStore {

	private final List<Account> accounts = List.of(
			new Account("U01", "Maya Brooks", "m.brooks@univ.edu", "SCHEDULER", List.of("CS", "MATH")),
			new Account("U02", "L. Bianchi", "l.bianchi@univ.edu", "INSTRUCTOR", List.of()),
			new Account("U03", "Jordan Ellis", "j.ellis@univ.edu", "STUDENT", List.of()));

	public List<Account> all() {
		return accounts;
	}

	public Optional<Account> find(String id) {
		return accounts.stream().filter(account -> account.id().equals(id)).findFirst();
	}

}