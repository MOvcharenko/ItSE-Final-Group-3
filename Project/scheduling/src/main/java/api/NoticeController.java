package api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import service.NoticeService;
import service.view.NoticeView;

/** Each user reads only their own notices (UC-18, TR3.2); the service takes the user from the token. */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

	private final NoticeService notices;

	public NoticeController(NoticeService notices) {
		this.notices = notices;
	}

	@GetMapping
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public List<NoticeView> listMine() {
		return notices.listMine();
	}

	@PostMapping("/{id}/read")
	@PreAuthorize("hasAnyRole('SCHEDULER','INSTRUCTOR','STUDENT')")
	public NoticeView markRead(@PathVariable("id") String id) {
		return notices.markRead(id);
	}
}
