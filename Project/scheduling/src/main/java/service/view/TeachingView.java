package service.view;

import java.util.List;

import domain.MeetingSlot;

/**
 * The caller's own sections in one term and what they add up to (My Teaching). Contact time counts weekly meetings
 * only, since one-time meetings and exams don't repeat.
 */
public record TeachingView(TermView term, List<Section> sections, Workload workload) {

	public record Section(String sectionId, String courseCode, String courseTitle, String sectionNumber, int creditHours,
			String roomName, String building, List<SectionView.InstructorView> instructors, List<MeetingSlot> meetings) {
	}

	public record Workload(int sections, int courses, int creditHours, int weeklyContactMinutes) {
	}
}
