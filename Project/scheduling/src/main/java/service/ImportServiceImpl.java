package service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import core.importing.ImportColumns;
import core.importing.ImportFileParser;
import core.importing.ImportRows.EquipmentRow;
import core.importing.ImportRows.RoomRow;
import core.importing.ImportRows.ScheduleRow;
import core.importing.ImportRows.SubjectRow;
import core.importing.Keys;
import core.importing.ParsedFile;
import core.importing.RowResult;
import domain.ImportType;
import domain.RowOutcome;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.ImportBatchEntity;
import persistence.ImportBatchRepository;
import persistence.RoomEntity;
import persistence.RoomRepository;
import persistence.SubjectEntity;
import persistence.SubjectRepository;
import service.view.ImportBatchView;
import service.view.ImportRowView;

/**
 * CSV import (FR3.3-FR3.7, UC-12, TR4.6).
 *
 * Deliberately NOT @Transactional: each row is committed by its own call to CatalogService (or SchedulePlacement),
 * and therefore in its own transaction. A row that fails rolls back alone, the good rows stay (UC-12 6a), and no
 * exception can mark a shared transaction rollback-only. The batch record is saved last, in one go.
 */
@Service
public class ImportServiceImpl implements ImportService {

	private static final Logger log = LoggerFactory.getLogger(ImportServiceImpl.class);

	private final CatalogService catalog;
	private final ImportBatchRepository batches;
	private final RoomRepository rooms;
	private final SubjectRepository subjects;
	private final AppUserRepository users;
	private final ObjectProvider<SchedulePlacement> placement;
	private final UserProfiles profiles;
	private final Caller caller;
	private final Clock clock;

	public ImportServiceImpl(CatalogService catalog, ImportBatchRepository batches, RoomRepository rooms,
			SubjectRepository subjects, AppUserRepository users, ObjectProvider<SchedulePlacement> placement,
			UserProfiles profiles, Caller caller, Clock clock) {
		this.catalog = catalog;
		this.batches = batches;
		this.rooms = rooms;
		this.subjects = subjects;
		this.users = users;
		this.placement = placement;
		this.profiles = profiles;
		this.caller = caller;
		this.clock = clock;
	}

	@Override
	public String getTemplate(ImportType type) {
		caller.requireScheduler();
		return ImportColumns.headerLine(type) + "\n";
	}

	@Override
	public ImportBatchView importFile(String fileName, ImportType type, String content) {
		caller.requireScheduler();
		Check.required(type, "Import type");
		String text = content == null ? "" : content;

		List<RowView> outcomes = switch (type) {
			case ROOMS -> commit(ImportFileParser.rooms(text), this::addRoom);
			case EQUIPMENT -> commit(ImportFileParser.equipment(text), this::addEquipment);
			case SUBJECT_LIST -> commit(ImportFileParser.subjects(text), this::addSubject);
			case READY_MADE_SCHEDULE -> commitSchedule(ImportFileParser.schedule(text));
		};

		profiles.ensure(caller);
		String name = Check.limit(fileName == null || fileName.isBlank() ? "upload.csv" : fileName.trim(), 255);
		ImportBatchEntity batch = ImportBatchEntity.create(name, type, LocalDateTime.now(clock), caller.id());
		outcomes.forEach(o -> batch.addRow(o.number(), o.outcome(), Check.limit(o.reason(), 500)));
		batches.save(batch);

		List<ImportRowView> rows = outcomes.stream()
				.map(o -> new ImportRowView(o.number(), o.outcome(), Check.limit(o.reason(), 500))).toList();
		long added = outcomes.stream().filter(o -> o.outcome() == RowOutcome.ADDED).count();
		return new ImportBatchView(batch.getId(), name, type, batch.getImportedAt(), (int) added,
				rows.size() - (int) added, rows);
	}

	// ---------------- per type ----------------

	private void addRoom(RoomRow r) {
		catalog.addRoom(r.name(), r.building(), r.capacity(), r.roomType(), r.fixedEquipment());
	}

	private void addEquipment(EquipmentRow r) {
		catalog.addEquipment(r.name(), r.itemType(), r.units(), r.homeLocation());
	}

	private void addSubject(SubjectRow r) {
		catalog.addSubject(r.courseCode(), r.title(), r.creditHours(), r.defaultRoomType(), null, r.typicalEquipment());
	}

	private List<RowView> commitSchedule(ParsedFile<ScheduleRow> file) {
		if (file.isRejected()) {
			throw new BusinessRuleException(file.fileError());
		}
		// Looked up once per file; matching ignores case and spacing ("Fall2026" = "Fall 2026").
		Map<String, RoomEntity> roomsByKey = new HashMap<>();
		rooms.findAll().forEach(r -> roomsByKey.put(Keys.of(r.getName()), r));
		Map<String, SubjectEntity> subjectsByKey = new HashMap<>();
		subjects.findAll().forEach(s -> subjectsByKey.put(Keys.of(s.getCourseCode()), s));

		return commit(file, row -> {
			SchedulePlacement place = placement.getIfAvailable();
			if (place == null) {
				throw new BusinessRuleException("Placing a ready-made schedule is not available yet.");
			}
			RoomEntity room = roomsByKey.get(Keys.of(row.roomName()));
			if (room == null) {
				throw new BusinessRuleException("room not recognized"); // exact reason of TC-FR3.4a
			}
			SubjectEntity subject = subjectsByKey.get(Keys.of(row.courseCode()));
			if (subject == null) {
				throw new BusinessRuleException("subject '" + row.courseCode()
						+ "' is not in the catalog (import the subject list first)");
			}
			List<String> instructorIds = new ArrayList<>();
			for (String name : row.instructorNames()) {
				List<AppUserEntity> found = users.findByDisplayNameIgnoreCase(name);
				if (found.isEmpty()) {
					throw new BusinessRuleException("instructor '" + name + "' not recognized");
				}
				if (found.size() > 1) {
					throw new BusinessRuleException("instructor name '" + name + "' matches more than one person");
				}
				instructorIds.add(found.get(0).getId());
			}
			place.placeImportedSection(row.termName(), subject.getId(), room.getId(), instructorIds, row.days(),
					row.start(), row.end());
		});
	}

	// ---------------- the per-row loop ----------------

	private record RowView(int number, RowOutcome outcome, String reason) {
	}

	private <T> List<RowView> commit(ParsedFile<T> file, Consumer<T> commitRow) {
		if (file.isRejected()) {
			throw new BusinessRuleException(file.fileError()); // wrong header etc: nothing is processed (UC-12 5a)
		}
		List<RowView> out = new ArrayList<>();
		for (RowResult<T> r : file.rows()) {
			if (r instanceof RowResult.Invalid<T> bad) {
				out.add(new RowView(bad.rowNumber(), RowOutcome.NEEDS_REVIEW, bad.reason()));
			} else if (r instanceof RowResult.Valid<T> ok) {
				out.add(tryCommit(ok, commitRow));
			}
		}
		return out;
	}

	private <T> RowView tryCommit(RowResult.Valid<T> row, Consumer<T> commitRow) {
		try {
			commitRow.accept(row.value());
			return new RowView(row.rowNumber(), RowOutcome.ADDED, null);
		} catch (BusinessRuleException | AccessDeniedException e) {
			return new RowView(row.rowNumber(), RowOutcome.NEEDS_REVIEW, e.getMessage());
		} catch (DataIntegrityViolationException e) {
			return new RowView(row.rowNumber(), RowOutcome.NEEDS_REVIEW,
					"this row duplicates an existing entry or conflicts with existing data");
		} catch (RuntimeException e) {
			log.error("Import row {} failed unexpectedly", row.rowNumber(), e);
			return new RowView(row.rowNumber(), RowOutcome.NEEDS_REVIEW,
					"this row could not be saved because of an unexpected problem");
		}
	}
}
