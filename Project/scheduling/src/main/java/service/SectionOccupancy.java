package service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import core.CalendarDates;
import domain.EquipmentNeed;
import domain.MeetingSlot;
import persistence.RoomEntity;
import persistence.SectionEntity;
import persistence.SectionRepository;
import persistence.TermEntity;
import persistence.TermRepository;

/**
 * What placed sections use in a time window, so availability search does not offer a room with a class in it (FR2.4,
 * UC-09). Drafts count, because they are the next term being planned, unless a Published term shares the window: a
 * copied term is a Draft with its source's dates, and counting both would use up the same equipment twice.
 */
@Service
@Transactional(readOnly = true)
public class SectionOccupancy implements TimetableOccupancy {

	private final TermRepository terms;
	private final SectionRepository sections;

	public SectionOccupancy(TermRepository terms, SectionRepository sections) {
		this.terms = terms;
		this.sections = sections;
	}

	@Override
	public Set<String> roomIdsInUse(LocalDateTime windowStart, LocalDateTime windowEnd) {
		return sectionsMeetingIn(windowStart, windowEnd).stream()
				.map(SectionEntity::getRoom)
				.filter(Objects::nonNull)
				.map(RoomEntity::getId)
				.collect(Collectors.toSet());
	}

	/** A section that meets several times in the window holds its units throughout, so it counts once. */
	@Override
	public Map<String, Integer> equipmentUnitsInUse(LocalDateTime windowStart, LocalDateTime windowEnd) {
		return sectionsMeetingIn(windowStart, windowEnd).stream()
				.flatMap(section -> section.getEquipment().stream())
				.collect(Collectors.toMap(EquipmentNeed::equipmentId, EquipmentNeed::units, Integer::sum));
	}

	private List<SectionEntity> sectionsMeetingIn(LocalDateTime windowStart, LocalDateTime windowEnd) {
		List<TermEntity> overlapping = terms.findOverlapping(windowStart.toLocalDate(), windowEnd.toLocalDate());
		List<TermEntity> published = overlapping.stream().filter(TermEntity::isPublished).toList();
		return (published.isEmpty() ? overlapping : published).stream()
				.flatMap(term -> sections.findForTerm(term.getId()).stream())
				.filter(section -> section.getMeetings().stream()
						.anyMatch(meeting -> meetsIn(meeting, section.getTerm(), windowStart, windowEnd)))
				.toList();
	}

	/** Touching is not overlapping: a class ending at 17:00 leaves the room free from 17:00. */
	private static boolean meetsIn(MeetingSlot meeting, TermEntity term, LocalDateTime windowStart, LocalDateTime windowEnd) {
		LocalDate from = windowStart.toLocalDate();
		LocalDate to = windowEnd.toLocalDate();
		return CalendarDates.datesOf(meeting, term.getStartDate(), term.getEndDate(), from, to).stream()
				.anyMatch(date -> date.atTime(meeting.start()).isBefore(windowEnd)
						&& windowStart.isBefore(date.atTime(meeting.end())));
	}

}
