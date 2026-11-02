package api.dto;

import java.util.List;

/** Body of PATCH /api/sections/{id}/assignment (FR1.6). A field left out keeps its current value. */
public record AssignmentRequest(String roomId, List<String> instructorIds) {
}
