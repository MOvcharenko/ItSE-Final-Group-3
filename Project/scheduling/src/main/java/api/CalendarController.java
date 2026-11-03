package api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import domain.CalendarScope;
import service.CalendarFilter;
import service.CalendarService;
import service.view.CalendarView;

/**
 * Calendar views (FR6.1), filters (FR6.2) and .ics export (FR6.3). The service limits the result to what the caller's
 * role may see.
 */
@RestController
@RequestMapping("/api")
public class CalendarController {

	private final CalendarService calendar;

	public CalendarController(CalendarService calendar) {
		this.calendar = calendar;
	}

	@GetMapping("/calendar")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public CalendarView calendar(
			@RequestParam(name = "view", defaultValue = "WEEK") CalendarScope view,
			@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(name = "termId", required = false) String termId,
			@RequestParam(name = "course", required = false) String course,
			@RequestParam(name = "instructor", required = false) String instructor,
			@RequestParam(name = "room", required = false) String room,
			@RequestParam(name = "building", required = false) String building) {
		return calendar.calendar(view, date, new CalendarFilter(termId, course, instructor, room, building));
	}

	/** Always one whole term, because repeating events do not fit a day or week window. */
	@GetMapping("/calendar.ics")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public ResponseEntity<String> exportIcs(
			@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@RequestParam(name = "termId", required = false) String termId,
			@RequestParam(name = "course", required = false) String course,
			@RequestParam(name = "instructor", required = false) String instructor,
			@RequestParam(name = "room", required = false) String room,
			@RequestParam(name = "building", required = false) String building) {
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"calendar.ics\"")
				.contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
				.body(calendar.exportIcs(date, new CalendarFilter(termId, course, instructor, room, building)));
	}
}
