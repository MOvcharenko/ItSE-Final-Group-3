package domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/** One meeting of a section (FR1.3): WEEKLY has a day, ONE_TIME and EXAM have a date. */
public record MeetingSlot(MeetingPattern pattern, DayOfWeek day, LocalDate date, LocalTime start, LocalTime end) {
}
