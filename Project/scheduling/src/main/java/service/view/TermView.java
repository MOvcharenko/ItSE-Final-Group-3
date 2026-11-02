package service.view;

import java.time.LocalDate;
import java.time.LocalDateTime;

import domain.TermStatus;

public record TermView(String id, String name, LocalDate startDate, LocalDate endDate, TermStatus status,
		LocalDateTime publishedAt) {
}
