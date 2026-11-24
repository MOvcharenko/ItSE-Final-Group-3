package api.dto;

import java.util.List;

import domain.EquipmentNeed;
import domain.MeetingSlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Request to commit a previewed SAT schedule into a Draft term (Dev B).
 */
public record AutoScheduleApplyRequest(
		Boolean replaceExisting,
		@NotEmpty List<@Valid PlacementToApply> placements
) {

	public record PlacementToApply(
			@NotBlank String subjectId,
			String sectionNumber,
			@NotBlank String roomId,
			@NotEmpty List<String> instructorIds,
			@NotEmpty List<MeetingSlot> meetings,
			List<EquipmentNeed> equipment
	) {
	}
}
