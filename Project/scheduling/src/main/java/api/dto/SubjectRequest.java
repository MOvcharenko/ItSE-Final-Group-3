package api.dto;

import java.util.List;

import domain.RoomType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/subjects (FR3.1, UC-11). The User's Guide says a subject "needs only a code, title,
 * credit hours and default room type", so department is optional here; the service decides what to do
 * when it is missing (e.g. use the Scheduler's only authorized department, otherwise reject).
 */
public record SubjectRequest(
		@NotBlank(message = "Course code is required") String courseCode,
		@NotBlank(message = "Title is required") String title,
		@Min(value = 1, message = "Credit hours must be at least 1") int creditHours,
		@NotNull(message = "Default room type is required") RoomType defaultRoomType,
		String department,
		List<String> typicalEquipment) {
}
