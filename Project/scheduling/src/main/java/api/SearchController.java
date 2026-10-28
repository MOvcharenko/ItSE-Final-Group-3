package api;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import domain.RoomType;
import service.BookingService;
import service.view.EquipmentView;
import service.view.RoomView;

/**
 * Availability search (FR2.4, UC-09). Read-only. start/end are ISO local date-times, e.g.
 * 2026-11-10T17:00:00. An invalid window (end not after start) is a business rule the service reports.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

	private final BookingService bookings;

	public SearchController(BookingService bookings) {
		this.bookings = bookings;
	}

	@GetMapping("/rooms")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public List<RoomView> rooms(
			@RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
			@RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
			@RequestParam(name = "minCapacity", required = false) Integer minCapacity,
			@RequestParam(name = "roomType", required = false) RoomType roomType,
			@RequestParam(name = "equipment", required = false) String equipment) {
		return bookings.searchRooms(start, end, minCapacity, roomType, equipment);
	}

	@GetMapping("/equipment")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public List<EquipmentView> equipment(
			@RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
			@RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
			@RequestParam(name = "itemType", required = false) String itemType) {
		return bookings.searchEquipment(start, end, itemType);
	}
}
