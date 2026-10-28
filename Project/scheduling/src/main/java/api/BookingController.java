package api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import api.dto.BookingRequest;
import api.dto.CancelRequest;
import domain.BookingStatus;
import jakarta.validation.Valid;
import service.BookingService;
import service.view.BookingView;

/**
 * The shared room/equipment request queue (FR2.3, TR4.5): one endpoint family, the resource type is a field.
 * Instructors may request and see their own requests (TR3.3); only Schedulers approve or cancel (UC-08).
 * An overlapping second approval comes back as 409 with an explanation (NFR3.1, TC-FR2.3b).
 */
@RestController
@RequestMapping("/api/bookings")
public class BookingController {

	private final BookingService bookings;

	public BookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public BookingView request(@Valid @RequestBody BookingRequest r) {
		return bookings.submitRequest(r.resourceType(), r.resourceId(), r.start(), r.end(), r.purpose());
	}

	/** Scheduler sees the whole queue, an Instructor only their own; the service decides which. */
	@GetMapping
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR')")
	public List<BookingView> list(@RequestParam(name = "status", required = false) BookingStatus status) {
		return bookings.list(status);
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("hasRole('SCHEDULER')")
	public BookingView approve(@PathVariable("id") String id) {
		return bookings.approve(id);
	}

	@PostMapping("/{id}/cancel")
	@PreAuthorize("hasRole('SCHEDULER')")
	public BookingView cancel(@PathVariable("id") String id,
			@Valid @RequestBody(required = false) CancelRequest body) {
		return bookings.cancel(id, body == null ? null : body.note());
	}
}
