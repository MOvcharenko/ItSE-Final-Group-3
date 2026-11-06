package service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import api.dto.AvailabilitySubmissionRequest;
import domain.AvailabilityStatus;
import domain.TermStatus;
import persistence.AppUserEntity;
import persistence.AppUserRepository;
import persistence.AvailabilitySubmissionEntity;
import persistence.AvailabilitySubmissionRepository;
import persistence.InstructorAvailabilityEntity;
import persistence.InstructorAvailabilityRepository;
import persistence.InstructorEquipmentNeedEntity;
import persistence.InstructorEquipmentNeedRepository;
import persistence.TermEntity;
import persistence.TermRepository;
import service.view.AvailabilityLensView;
import service.view.AvailabilityView;

@Service
public class DefaultAvailabilityService implements AvailabilityService {

	private final InstructorAvailabilityRepository availabilityRepo;
	private final AvailabilitySubmissionRepository submissionRepo;
	private final InstructorEquipmentNeedRepository equipmentNeedRepo;
	private final TermRepository terms;
	private final AppUserRepository users;
	private final ConflictService conflicts;
	private final Caller caller;
	private final UserProfiles userProfiles;
	private final Clock clock;

	public DefaultAvailabilityService(
			InstructorAvailabilityRepository availabilityRepo,
			AvailabilitySubmissionRepository submissionRepo,
			InstructorEquipmentNeedRepository equipmentNeedRepo,
			TermRepository terms,
			AppUserRepository users,
			ConflictService conflicts,
			Caller caller,
			UserProfiles userProfiles,
			Clock clock
	) {
		this.availabilityRepo = availabilityRepo;
		this.submissionRepo = submissionRepo;
		this.equipmentNeedRepo = equipmentNeedRepo;
		this.terms = terms;
		this.users = users;
		this.conflicts = conflicts;
		this.caller = caller;
		this.userProfiles = userProfiles;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public AvailabilityView getMyAvailability(String termId) {
		if (!caller.isInstructor()) {
			throw new AccessDeniedException("Only instructors can view their availability submission.");
		}
		TermEntity term = terms.findById(termId)
				.orElseThrow(() -> NotFoundException.of("Term", termId));

		AppUserEntity instructor = userProfiles.ensure(caller);
		return buildView(term, instructor);
	}

	@Override
	@Transactional
	public AvailabilityView submitMyAvailability(String termId, AvailabilitySubmissionRequest request) {
		if (!caller.isInstructor()) {
			throw new AccessDeniedException("Only instructors can submit availability.");
		}
		TermEntity term = terms.findById(termId)
				.orElseThrow(() -> NotFoundException.of("Term", termId));

		// NFR3.4 / UC-14 extension 6a: Published term locks availability
		if (term.getStatus() == TermStatus.PUBLISHED) {
			throw new BusinessRuleException(
					"Term " + term.getName() + " is published. Instructor availability is locked and cannot be changed retroactively.");
		}

		AppUserEntity instructor = userProfiles.ensure(caller);

		// Validate blocks (start < end)
		if (request.blocks() != null) {
			for (var b : request.blocks()) {
				if (!b.startTime().isBefore(b.endTime())) {
					throw new BusinessRuleException("Availability window start time must be before end time: "
							+ b.startTime() + " - " + b.endTime());
				}
			}
		}

		// 1. Delete existing records for this instructor in this term
		availabilityRepo.deleteByTerm_IdAndInstructor_Id(term.getId(), instructor.getId());
		equipmentNeedRepo.deleteByTerm_IdAndInstructor_Id(term.getId(), instructor.getId());

		// 2. Insert new blocks
		if (request.blocks() != null) {
			for (var b : request.blocks()) {
				InstructorAvailabilityEntity entity = InstructorAvailabilityEntity.create(
						term, instructor, b.dayOfWeek(), b.startTime(), b.endTime(), b.status()
				);
				availabilityRepo.save(entity);
			}
		}

		// 3. Insert equipment needs
		if (request.equipmentNeeds() != null) {
			for (String itemType : request.equipmentNeeds()) {
				if (itemType != null && !itemType.isBlank()) {
					InstructorEquipmentNeedEntity need = new InstructorEquipmentNeedEntity(
							term, instructor, itemType.trim()
					);
					equipmentNeedRepo.save(need);
				}
			}
		}

		// 4. Upsert submission entry
		LocalDateTime now = LocalDateTime.now(clock);
		AvailabilitySubmissionEntity submission = submissionRepo.findByTermAndInstructor(term.getId(), instructor.getId())
				.orElseGet(() -> new AvailabilitySubmissionEntity(term, instructor, request.notes(), now));
		submission.setNotes(request.notes());
		submission.setSubmittedAt(now);
		submissionRepo.save(submission);

		// 5. Trigger conflict recheck (FR4, UC-05, UC-06)
		conflicts.recheck(term.getId(), ConflictService.Ref.availability(instructor.getId()));

		return buildView(term, instructor);
	}

	@Override
	@Transactional(readOnly = true)
	public AvailabilityLensView getInstructorAvailability(String instructorId, String termId) {
		caller.requireSchedulerOrInstructor();

		AppUserEntity instructor = users.findById(instructorId)
				.orElseThrow(() -> NotFoundException.of("Instructor", instructorId));

		TermEntity term = terms.findById(termId)
				.orElseThrow(() -> NotFoundException.of("Term", termId));

		List<InstructorAvailabilityEntity> blocks = availabilityRepo.findByTermAndInstructor(term.getId(), instructor.getId());

		List<AvailabilityLensView.LensBlock> lensBlocks = blocks.stream()
				.map(b -> new AvailabilityLensView.LensBlock(b.getDayOfWeek(), b.getStartTime(), b.getEndTime(), b.getStatus()))
				.toList();

		return new AvailabilityLensView(instructor.getId(), instructor.getDisplayName(), term.getId(), lensBlocks);
	}

	private AvailabilityView buildView(TermEntity term, AppUserEntity instructor) {
		List<InstructorAvailabilityEntity> blocks = availabilityRepo.findByTermAndInstructor(term.getId(), instructor.getId());
		List<InstructorEquipmentNeedEntity> needs = equipmentNeedRepo.findByTermAndInstructor(term.getId(), instructor.getId());
		AvailabilitySubmissionEntity submission = submissionRepo.findByTermAndInstructor(term.getId(), instructor.getId()).orElse(null);

		double openHours = 0;
		double preferHours = 0;
		double unavailHours = 0;

		List<AvailabilityView.BlockView> blockViews = new ArrayList<>();
		for (InstructorAvailabilityEntity b : blocks) {
			double hours = Duration.between(b.getStartTime(), b.getEndTime()).toMinutes() / 60.0;
			if (b.getStatus() == AvailabilityStatus.OPEN) {
				openHours += hours;
			} else if (b.getStatus() == AvailabilityStatus.PREFER) {
				preferHours += hours;
			} else if (b.getStatus() == AvailabilityStatus.UNAVAILABLE) {
				unavailHours += hours;
			}
			blockViews.add(new AvailabilityView.BlockView(
					b.getId(), b.getDayOfWeek(), b.getStartTime(), b.getEndTime(), b.getStatus()
			));
		}

		AvailabilityView.Summary summary = new AvailabilityView.Summary(
				openHours, preferHours, unavailHours, openHours + preferHours + unavailHours
		);

		List<String> equipmentList = needs.stream().map(InstructorEquipmentNeedEntity::getItemType).toList();

		boolean readOnly = term.getStatus() == TermStatus.PUBLISHED;

		return new AvailabilityView(
				term.getId(),
				instructor.getId(),
				instructor.getDisplayName(),
				readOnly,
				blockViews,
				equipmentList,
				submission != null ? submission.getNotes() : null,
				submission != null ? submission.getSubmittedAt() : null,
				summary
		);
	}
}
