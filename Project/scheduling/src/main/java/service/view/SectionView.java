package service.view;

import java.util.List;

import domain.EquipmentNeed;
import domain.MeetingSlot;

public record SectionView(String id, String termId, String subjectId, String courseCode, String sectionNumber,
		String roomId, String roomName, List<InstructorView> instructors, List<MeetingSlot> meetings,
		List<EquipmentNeed> equipment) {

	public record InstructorView(String id, String name) {
	}
}
