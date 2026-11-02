package service;

import java.util.List;

import domain.EquipmentNeed;
import domain.MeetingSlot;

/**
 * Everything needed to place a section, from the API or from a schedule import. {@link TimetableService} checks it,
 * so both get the same rules and wording. sectionNumber and equipment are optional.
 */
public record SectionPlacement(String subjectId, String sectionNumber, String roomId, List<String> instructorIds,
		List<MeetingSlot> meetings, List<EquipmentNeed> equipment) {
}
