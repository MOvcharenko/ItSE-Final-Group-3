package domain;

/** Catalog status; MAINTENANCE and CHECKED_OUT are hidden from search (FR2.4, FR2.5). */
public enum ResourceStatus {
	AVAILABLE,
	MAINTENANCE,
	CHECKED_OUT
}
