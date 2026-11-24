package core.solver;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import core.MeetingTime;
import domain.MeetingPattern;
import domain.MeetingSlot;

/**
 * Standard weekly schedule patterns for the SAT auto-scheduler (framework-free).
 * Band discretization (6 daily bands, 08:00–20:00) lets the MaxSAT objective penalize
 * long gaps / split days (such as 09:00 + 18:00) cleanly with compact clauses.
 */
public record SlotPattern(
		String id,
		String label,
		List<MeetingSlot> meetings,
		int totalWeeklyMinutes,
		boolean edgeHours
) {

	public record DayBand(DayOfWeek day, int band) {
	}

	public static final int BAND_COUNT = 6;
	private static final LocalTime[] BAND_STARTS = {
			LocalTime.of(8, 0),
			LocalTime.of(10, 0),
			LocalTime.of(12, 0),
			LocalTime.of(14, 0),
			LocalTime.of(16, 0),
			LocalTime.of(17, 30)
	};
	private static final LocalTime[] BAND_ENDS = {
			LocalTime.of(10, 0),
			LocalTime.of(12, 0),
			LocalTime.of(14, 0),
			LocalTime.of(16, 0),
			LocalTime.of(17, 30),
			LocalTime.of(20, 0)
	};
	private static final LocalTime PRIME_START = LocalTime.of(9, 0);
	private static final LocalTime PRIME_END = LocalTime.of(17, 0);

	public SlotPattern {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(label, "label must not be null");
		meetings = meetings == null ? List.of() : List.copyOf(meetings);
	}

	public boolean overlaps(SlotPattern other) {
		if (other == null) {
			return false;
		}
		for (MeetingSlot m1 : this.meetings) {
			MeetingTime mt1 = MeetingTime.fromSlot(m1);
			for (MeetingSlot m2 : other.meetings) {
				if (mt1.overlaps(MeetingTime.fromSlot(m2))) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean overlaps(MeetingTime other) {
		if (other == null) {
			return false;
		}
		for (MeetingSlot m : this.meetings) {
			if (MeetingTime.fromSlot(m).overlaps(other)) {
				return true;
			}
		}
		return false;
	}

	public Set<DayBand> occupiedBands() {
		Set<DayBand> bands = new LinkedHashSet<>();
		for (MeetingSlot slot : meetings) {
			DayOfWeek day = slot.day();
			if (day == null) {
				continue;
			}
			for (int b = 0; b < BAND_COUNT; b++) {
				if (slot.start().isBefore(BAND_ENDS[b]) && BAND_STARTS[b].isBefore(slot.end())) {
					bands.add(new DayBand(day, b));
				}
			}
		}
		return bands;
	}

	public static Set<DayBand> occupiedBandsFor(List<MeetingTime> meetings) {
		Set<DayBand> bands = new LinkedHashSet<>();
		if (meetings == null) {
			return bands;
		}
		for (MeetingTime mt : meetings) {
			DayOfWeek day = mt.dayOfWeek();
			if (day == null) {
				continue;
			}
			for (int b = 0; b < BAND_COUNT; b++) {
				if (mt.start().isBefore(BAND_ENDS[b]) && BAND_STARTS[b].isBefore(mt.end())) {
					bands.add(new DayBand(day, b));
				}
			}
		}
		return bands;
	}

	/**
	 * Generates candidate weekly slot patterns matching {@code creditHours} and {@code patternStyle}.
	 * Prime daytime patterns (09:00–17:00) are placed first for deterministic tie-breaking.
	 */
	public static List<SlotPattern> standardPatterns(int creditHours, String patternStyle) {
		String style = patternStyle == null || patternStyle.isBlank()
				? "AUTO"
				: patternStyle.trim().toUpperCase(Locale.ROOT);

		List<SlotPattern> all = new ArrayList<>();
		if (creditHours <= 2 || "SINGLE".equals(style)) {
			int duration = Math.max(50, Math.min(120, Math.max(1, creditHours) * 50));
			LocalTime[] starts = {
					LocalTime.of(9, 30), LocalTime.of(11, 0), LocalTime.of(13, 0),
					LocalTime.of(14, 30), LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(17, 30)
			};
			DayOfWeek[] weekdays = {
					DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
					DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
			};
			for (LocalTime start : starts) {
				LocalTime end = start.plusMinutes(duration);
				for (DayOfWeek day : weekdays) {
					String tag = shortDay(day);
					all.add(pattern("SINGLE_" + tag + "_" + hhmmCompact(start),
							tag + " " + hhmm(start) + "-" + hhmm(end),
							List.of(day), start, end));
				}
			}
		} else if (creditHours == 3) {
			if ("AUTO".equals(style) || "TTH".equals(style)) {
				addMultiDayPatterns(all, "TTH", "TTh",
						List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), 75,
						LocalTime.of(9, 30), LocalTime.of(11, 0), LocalTime.of(13, 0),
						LocalTime.of(14, 30), LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(17, 30));
			}
			if ("AUTO".equals(style) || "MWF".equals(style)) {
				addMultiDayPatterns(all, "MWF", "MWF",
						List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 50,
						LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
						LocalTime.of(13, 0), LocalTime.of(14, 0), LocalTime.of(15, 0),
						LocalTime.of(16, 0), LocalTime.of(8, 0), LocalTime.of(17, 30));
			}
			if ("AUTO".equals(style) || "MW".equals(style)) {
				addMultiDayPatterns(all, "MW", "MW",
						List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 75,
						LocalTime.of(9, 30), LocalTime.of(11, 0), LocalTime.of(13, 0),
						LocalTime.of(14, 30), LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(17, 30));
			}
		} else {
			if ("AUTO".equals(style) || "MW".equals(style)) {
				addMultiDayPatterns(all, "MW", "MW",
						List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 100,
						LocalTime.of(10, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
						LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(17, 30));
			}
			if ("AUTO".equals(style) || "TTH".equals(style)) {
				addMultiDayPatterns(all, "TTH", "TTh",
						List.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), 100,
						LocalTime.of(10, 0), LocalTime.of(12, 0), LocalTime.of(14, 0),
						LocalTime.of(8, 0), LocalTime.of(16, 0), LocalTime.of(17, 30));
			}
			if ("AUTO".equals(style) || "MWF".equals(style)) {
				addMultiDayPatterns(all, "MWF", "MWF",
						List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 75,
						LocalTime.of(9, 0), LocalTime.of(10, 30), LocalTime.of(13, 0),
						LocalTime.of(14, 30), LocalTime.of(16, 0));
			}
		}

		// Sort prime daytime hours first, then by id for full determinism
		List<SlotPattern> primeFirst = new ArrayList<>(all.size());
		all.stream().filter(p -> !p.edgeHours()).forEach(primeFirst::add);
		all.stream().filter(SlotPattern::edgeHours).forEach(primeFirst::add);
		return List.copyOf(primeFirst);
	}

	private static void addMultiDayPatterns(List<SlotPattern> target, String prefix, String labelPrefix,
			List<DayOfWeek> days, int durationMinutes, LocalTime... starts) {
		for (LocalTime start : starts) {
			LocalTime end = start.plusMinutes(durationMinutes);
			target.add(pattern(prefix + "_" + hhmmCompact(start),
					labelPrefix + " " + hhmm(start) + "-" + hhmm(end),
					days, start, end));
		}
	}

	private static SlotPattern pattern(String id, String label, List<DayOfWeek> days, LocalTime start, LocalTime end) {
		List<MeetingSlot> slots = days.stream()
				.map(d -> new MeetingSlot(MeetingPattern.WEEKLY, d, null, start, end))
				.toList();
		int weeklyMinutes = (int) java.time.Duration.between(start, end).toMinutes() * days.size();
		boolean edge = start.isBefore(PRIME_START) || end.isAfter(PRIME_END);
		return new SlotPattern(id, label, slots, weeklyMinutes, edge);
	}

	private static String shortDay(DayOfWeek day) {
		return switch (day) {
			case MONDAY -> "Mon";
			case TUESDAY -> "Tue";
			case WEDNESDAY -> "Wed";
			case THURSDAY -> "Thu";
			case FRIDAY -> "Fri";
			case SATURDAY -> "Sat";
			case SUNDAY -> "Sun";
		};
	}

	private static String hhmm(LocalTime time) {
		return String.format("%02d:%02d", time.getHour(), time.getMinute());
	}

	private static String hhmmCompact(LocalTime time) {
		return String.format("%02d%02d", time.getHour(), time.getMinute());
	}
}
