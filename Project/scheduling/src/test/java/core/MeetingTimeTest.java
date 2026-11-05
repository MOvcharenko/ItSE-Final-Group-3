package core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import domain.MeetingPattern;
import domain.MeetingSlot;

class MeetingTimeTest {

	@Test
	@DisplayName("Weekly meetings on same day with overlapping times overlap")
	void weeklyOverlapping() {
		MeetingTime m1 = MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15");
		MeetingTime m2 = MeetingTime.weekly(DayOfWeek.MONDAY, "13:30", "14:30");

		assertThat(m1.overlaps(m2)).isTrue();
		assertThat(m2.overlaps(m1)).isTrue();
		assertThat(m1.overlapDuration(m2)).isEqualTo(Duration.ofMinutes(45));
	}

	@Test
	@DisplayName("Back-to-back meeting windows touch but do not overlap")
	void backToBackDoNotOverlap() {
		MeetingTime m1 = MeetingTime.weekly(DayOfWeek.MONDAY, "10:00", "11:15");
		MeetingTime m2 = MeetingTime.weekly(DayOfWeek.MONDAY, "11:15", "12:00");

		assertThat(m1.overlaps(m2)).isFalse();
		assertThat(m2.overlaps(m1)).isFalse();
		assertThat(m1.overlapDuration(m2)).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("Weekly meetings on different days do not overlap")
	void differentDaysDoNotOverlap() {
		MeetingTime m1 = MeetingTime.weekly(DayOfWeek.MONDAY, "10:00", "11:15");
		MeetingTime m2 = MeetingTime.weekly(DayOfWeek.TUESDAY, "10:00", "11:15");

		assertThat(m1.overlaps(m2)).isFalse();
		assertThat(m1.overlapDuration(m2)).isEqualTo(Duration.ZERO);
	}

	@Test
	@DisplayName("One-time meetings on same date overlap when times overlap")
	void oneTimeSameDate() {
		LocalDate date = LocalDate.of(2026, 11, 10);
		MeetingTime m1 = MeetingTime.oneTime(date, "09:00", "11:00");
		MeetingTime m2 = MeetingTime.oneTime(date, "10:00", "12:00");

		assertThat(m1.overlaps(m2)).isTrue();
		assertThat(m1.overlapDuration(m2)).isEqualTo(Duration.ofMinutes(60));
	}

	@Test
	@DisplayName("One-time meetings on different dates do not overlap")
	void oneTimeDifferentDates() {
		MeetingTime m1 = MeetingTime.oneTime(LocalDate.of(2026, 11, 10), "09:00", "11:00");
		MeetingTime m2 = MeetingTime.oneTime(LocalDate.of(2026, 11, 11), "09:00", "11:00");

		assertThat(m1.overlaps(m2)).isFalse();
	}

	@Test
	@DisplayName("Weekly meeting and dated meeting overlap when date matches day of week and times overlap")
	void weeklyAndDatedOverlap() {
		// 2026-11-09 is a Monday
		LocalDate monday = LocalDate.of(2026, 11, 9);
		assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

		MeetingTime weekly = MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15");
		MeetingTime exam = MeetingTime.exam(monday, "13:30", "15:00");

		assertThat(weekly.overlaps(exam)).isTrue();
		assertThat(exam.overlaps(weekly)).isTrue();
		assertThat(weekly.overlapDuration(exam)).isEqualTo(Duration.ofMinutes(45));
	}

	@Test
	@DisplayName("Weekly meeting and dated meeting do not overlap when date falls on different day of week")
	void weeklyAndDatedDifferentDay() {
		// 2026-11-10 is a Tuesday
		LocalDate tuesday = LocalDate.of(2026, 11, 10);
		MeetingTime weekly = MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15");
		MeetingTime exam = MeetingTime.exam(tuesday, "13:00", "14:15");

		assertThat(weekly.overlaps(exam)).isFalse();
	}

	@Test
	@DisplayName("overlaps with DayOfWeek and LocalTimes correctly detects overlap and touching boundaries")
	void dayAndTimeOverlaps() {
		MeetingTime m = MeetingTime.weekly(DayOfWeek.MONDAY, "13:00", "14:15");

		// Overlapping
		assertThat(m.overlaps(DayOfWeek.MONDAY, LocalTime.of(12, 0), LocalTime.of(14, 0))).isTrue();
		// Boundary touching at end (14:15)
		assertThat(m.overlaps(DayOfWeek.MONDAY, LocalTime.of(14, 15), LocalTime.of(16, 0))).isFalse();
		// Boundary touching at start (13:00)
		assertThat(m.overlaps(DayOfWeek.MONDAY, LocalTime.of(11, 0), LocalTime.of(13, 0))).isFalse();
		// Different day
		assertThat(m.overlaps(DayOfWeek.TUESDAY, LocalTime.of(12, 0), LocalTime.of(14, 0))).isFalse();
	}

	@Test
	@DisplayName("Conversion from domain MeetingSlot preserves values")
	void fromSlot() {
		MeetingSlot slot = new MeetingSlot(MeetingPattern.WEEKLY, DayOfWeek.WEDNESDAY, null,
				LocalTime.of(9, 0), LocalTime.of(10, 15));
		MeetingTime mt = MeetingTime.fromSlot(slot);

		assertThat(mt.pattern()).isEqualTo(MeetingPattern.WEEKLY);
		assertThat(mt.dayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
		assertThat(mt.start()).isEqualTo(LocalTime.of(9, 0));
		assertThat(mt.end()).isEqualTo(LocalTime.of(10, 15));
	}

	@Test
	@DisplayName("Invalid window where start >= end throws IllegalArgumentException")
	void invalidWindowThrows() {
		assertThatThrownBy(() -> MeetingTime.weekly(DayOfWeek.MONDAY, "14:00", "13:00"))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> MeetingTime.weekly(DayOfWeek.MONDAY, "14:00", "14:00"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Weekly meeting requires non-null day of week")
	void weeklyRequiresDay() {
		assertThatThrownBy(() -> new MeetingTime(MeetingPattern.WEEKLY, null, null, LocalTime.of(9, 0), LocalTime.of(10, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Exam or OneTime meeting requires non-null date")
	void nonWeeklyRequiresDate() {
		assertThatThrownBy(() -> new MeetingTime(MeetingPattern.EXAM, DayOfWeek.MONDAY, null, LocalTime.of(9, 0), LocalTime.of(10, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
