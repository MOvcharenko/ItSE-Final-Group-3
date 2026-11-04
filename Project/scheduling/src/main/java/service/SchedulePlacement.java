package service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * How a ready-made schedule row gets into the DRAFT timetable (FR3.4, FR3.7, UC-12 step 6 and 8).
 * Implemented by {@link ImportedSchedulePlacement}.
 *
 * Contract, one call = one section:
 *  - runs in its OWN transaction and must not depend on an outer one (a bad row must not undo the good ones);
 *  - looks the term up by name, ignoring case and spaces (Keys.of: the template says "Fall2026", the term is
 *    "Fall 2026"), and requires it to be in Draft and the caller to be authorized for the subject's department;
 *  - creates the section with one weekly meeting per day, the room and the instructors;
 *  - runs conflict detection straight away, exactly as for a section placed by hand (FR3.7);
 *  - on any problem throws BusinessRuleException with a plain-language reason; that reason becomes the row's
 *    "Needs review" text (e.g. "term 'Fall2027' not found", "term is already Published").
 */
public interface SchedulePlacement {

	void placeImportedSection(String termName, String subjectId, String roomId, List<String> instructorIds,
			List<DayOfWeek> days, LocalTime start, LocalTime end);
}
