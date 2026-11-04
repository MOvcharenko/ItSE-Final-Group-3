package service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import core.importing.Keys;
import domain.MeetingPattern;
import domain.MeetingSlot;
import persistence.SectionEntity;
import persistence.SectionRepository;
import persistence.TermEntity;
import persistence.TermRepository;

/**
 * Places one ready-made schedule row through {@link TimetableService}, so it gets the same rules and conflict re-check
 * as a section placed by hand (FR3.4, FR3.7). The file has no section numbers, so a course's rows are numbered in
 * order. A row matching a section already in the term is refused, so uploading a file twice adds nothing.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ImportedSchedulePlacement implements SchedulePlacement {

	private final TermRepository terms;
	private final SectionRepository sections;
	private final TimetableService timetable;

	public ImportedSchedulePlacement(TermRepository terms, SectionRepository sections, TimetableService timetable) {
		this.terms = terms;
		this.sections = sections;
		this.timetable = timetable;
	}

	@Override
	public void placeImportedSection(String termName, String subjectId, String roomId, List<String> instructorIds,
			List<DayOfWeek> days, LocalTime start, LocalTime end) {
		String termId = termIdNamed(termName);
		TermEntity term = terms.lockById(termId).orElseThrow(() -> termNotFound(termName));
		if (term.isPublished()) {
			throw new BusinessRuleException("term '" + termName + "' is already Published");
		}
		List<MeetingSlot> meetings = days.stream()
				.map(day -> new MeetingSlot(MeetingPattern.WEEKLY, day, null, start, end))
				.toList();
		List<SectionEntity> existing = sections.findByTerm_IdAndSubject_Id(termId, subjectId);
		if (existing.stream().anyMatch(section -> isSameSection(section, roomId, instructorIds, meetings))) {
			throw new BusinessRuleException("this section is already in term '" + termName + "'");
		}
		timetable.placeSection(termId, new SectionPlacement(subjectId, nextFreeNumber(existing), roomId, instructorIds,
				meetings, List.of()));
	}

	/** Reads only ids and names, so the term is first loaded by the lock and its status is up to date. */
	private String termIdNamed(String termName) {
		String key = Keys.of(termName);
		List<String> found = terms.findAllNames().stream()
				.filter(term -> Keys.of(term.getName()).equals(key))
				.map(TermRepository.TermName::getId)
				.toList();
		if (found.isEmpty()) {
			throw termNotFound(termName);
		}
		if (found.size() > 1) {
			throw new BusinessRuleException("term name '" + termName + "' matches more than one term");
		}
		return found.get(0);
	}

	private static BusinessRuleException termNotFound(String termName) {
		return new BusinessRuleException("term '" + termName + "' not found");
	}

	private static boolean isSameSection(SectionEntity section, String roomId, List<String> instructorIds,
			List<MeetingSlot> meetings) {
		return section.getRoom() != null && section.getRoom().getId().equals(roomId)
				&& section.getInstructorIds().equals(Set.copyOf(instructorIds))
				&& new HashSet<>(section.getMeetings()).equals(Set.copyOf(meetings));
	}

	private static String nextFreeNumber(List<SectionEntity> existing) {
		Set<String> taken = existing.stream().map(SectionEntity::getSectionNumber).collect(Collectors.toSet());
		int number = 1;
		while (taken.contains(numbered(number))) {
			number++;
		}
		return numbered(number);
	}

	private static String numbered(int number) {
		return String.format(Locale.ROOT, "%02d", number);
	}

}
