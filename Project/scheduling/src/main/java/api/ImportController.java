package api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import domain.ImportType;
import service.BusinessRuleException;
import service.ImportService;
import service.NotFoundException;
import service.view.ImportBatchView;

/**
 * CSV import (FR3.3-FR3.7). The {type} path segment is the kebab-case name of the import type:
 * ready-made-schedule, subject-list, rooms, equipment.
 * A partly faulty file is NOT an error: the response is 200 with a per-row outcome (FR3.6, TR4.6).
 * Only a wrong header or an unreadable/empty file is rejected, as 422.
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

	private final ImportService imports;

	public ImportController(ImportService imports) {
		this.imports = imports;
	}

	@GetMapping("/templates/{type}")
	@PreAuthorize("hasRole('SCHEDULER')")
	public ResponseEntity<String> template(@PathVariable("type") String type) {
		ImportType t = parseType(type);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + slugOf(t) + "-template.csv\"")
				.contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
				.body(imports.getTemplate(t));
	}

	@PostMapping(value = "/{type}", consumes = "multipart/form-data")
	@PreAuthorize("hasRole('SCHEDULER')")
	public ImportBatchView upload(@PathVariable("type") String type, @RequestPart("file") MultipartFile file) {
		ImportType t = parseType(type);
		if (file.isEmpty()) {
			throw new BusinessRuleException("The uploaded file is empty. Download the template and fill it in.");
		}
		return imports.importFile(file.getOriginalFilename(), t, readUtf8(file));
	}

	private static String readUtf8(MultipartFile file) {
		try {
			String text = new String(file.getBytes(), StandardCharsets.UTF_8);
			// Excel's "CSV UTF-8" puts a byte-order mark in front of the header and would break the header check.
			return text.startsWith("\uFEFF") ? text.substring(1) : text;
		} catch (IOException e) {
			throw new BusinessRuleException("The uploaded file could not be read. Please try uploading it again.");
		}
	}

	private static ImportType parseType(String slug) {
		for (ImportType t : ImportType.values()) {
			if (slugOf(t).equals(slug)) {
				return t;
			}
		}
		throw new NotFoundException("Unknown import type '" + slug
				+ "'. Use ready-made-schedule, subject-list, rooms or equipment.");
	}

	private static String slugOf(ImportType t) {
		return t.name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
