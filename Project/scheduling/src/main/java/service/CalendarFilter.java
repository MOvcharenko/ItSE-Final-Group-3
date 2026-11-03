package service;

/**
 * Optional calendar filters (FR6.2); null means no filter. Course and building ignore case. A blank value also means
 * no filter, because a web form sends its unset fields as empty parameters.
 */
public record CalendarFilter(String termId, String course, String instructorId, String roomId, String building) {

	public CalendarFilter {
		termId = blankToNull(termId);
		course = blankToNull(course);
		instructorId = blankToNull(instructorId);
		roomId = blankToNull(roomId);
		building = blankToNull(building);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

}
