package main.java.team3.coreplatform;

import java.util.List;

/** One of the three fixed test accounts. The real Core Platform owns these. */
public record Account(String id, String name, String email, String role, List<String> departments) {

	public Account {
		departments = List.copyOf(departments);
	}

	public String initials() {
		String[] parts = name.split("\\s+");
		return parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "");
	}

}