package core.solver;

import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.pb.IPBSolver;
import org.sat4j.pb.ObjectiveFunction;
import org.sat4j.pb.PseudoOptDecorator;
import org.sat4j.pb.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

import core.MeetingTime;
import core.conflict.ConflictRules;
import core.conflict.EquipmentInfo;
import core.conflict.InstructorAvailabilityBlock;
import core.conflict.ScheduledBooking;
import core.conflict.ScheduledMeeting;
import core.solver.AutoScheduleInput.InstructorOption;
import core.solver.AutoScheduleInput.PlacementKey;
import core.solver.AutoScheduleInput.PreviousSolution;
import core.solver.AutoScheduleInput.RoomOption;
import core.solver.AutoScheduleInput.SectionRequest;
import core.solver.AutoScheduleInput.SolverOptions;
import core.solver.AutoScheduleResult.ProposedPlacement;
import core.solver.AutoScheduleResult.QualitySummary;
import domain.BookingStatus;
import domain.EquipmentNeed;
import domain.MeetingSlot;
import domain.ResourceType;

/**
 * Pure-Java Weighted PB-MaxSAT timetable generator powered by Sat4j (Dev B).
 * Encodes all hard scheduling constraints (zero conflicts) and optimizes human convenience
 * via linear soft penalties (avoiding split days such as 09:00 + 18:00, preferring prime daytime hours,
 * honoring instructor PREFER blocks, and right-sizing rooms).
 */
public final class TimetableSatSolver {

	private static final DayOfWeek[] WEEKDAYS = {
			DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
	};

	private static final int SPLIT_DAY_PENALTY = 40;
	private static final int WIDE_SPAN_PENALTY = 18;
	private static final int MARATHON_PENALTY = 15;
	private static final int DEPT_SPLIT_PENALTY = 25;
	private static final int DEPT_SPAN_PENALTY = 10;
	private static final int EDGE_HOURS_PENALTY = 10;
	private static final int MISSED_PREFER_PENALTY = 12;
	private static final int UNDERFILLED_ROOM_PENALTY = 8;
	private static final int MODERATE_FILL_PENALTY = 3;
	private static final int SPLIT_SPAN_THRESHOLD_MINUTES = 360;

	public AutoScheduleResult solve(AutoScheduleInput input) {
		long startedAt = System.nanoTime();
		if (input == null || input.requests().isEmpty()) {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
			return new AutoScheduleResult(true, "OPTIMAL", List.of(),
					QualitySummary.empty(elapsedMs, 0, 0),
					List.of("All eligible subjects are already scheduled."));
		}

		PreparedProblem prep = prepare(input);
		if (!prep.diagnostics.isEmpty()) {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
			return new AutoScheduleResult(false, "UNSAT", List.of(),
					QualitySummary.empty(elapsedMs, 0, 0), prep.diagnostics);
		}

		// When excludedSolutions are provided ("Try Another Option"), first try pattern-level blocking so classes
		// visibly move to different times; if no distinct times remain, fall back to full-tuple blocking.
		if (!input.excludedSolutions().isEmpty()) {
			AutoScheduleResult patternBlocked = solveWithBlocking(input, prep, true, startedAt);
			if (patternBlocked.solvable()) {
				return patternBlocked;
			}
			AutoScheduleResult tupleBlocked = solveWithBlocking(input, prep, false, startedAt);
			if (tupleBlocked.solvable()) {
				return tupleBlocked;
			}
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
			return new AutoScheduleResult(false, "NO_MORE_OPTIONS", List.of(),
					QualitySummary.empty(elapsedMs, tupleBlocked.summary().satVariables(), tupleBlocked.summary().satClauses()),
					List.of("No other conflict-free schedule options exist under the current constraints."));
		}

		return solveWithBlocking(input, prep, false, startedAt);
	}

	// ---- Pre-validation & Domain Pruning ----

	private PreparedProblem prepare(AutoScheduleInput input) {
		List<SectionRequest> requests = input.requests();
		Map<String, InstructorOption> instructorById = new LinkedHashMap<>();
		for (InstructorOption inst : input.instructors()) {
			instructorById.put(inst.instructorId(), inst);
		}

		Map<String, List<ScheduledMeeting>> fixedByRoom = new HashMap<>();
		Map<String, List<ScheduledMeeting>> fixedByInstructor = new HashMap<>();
		for (ScheduledMeeting fm : input.fixedSections()) {
			if (fm.roomId() != null) {
				fixedByRoom.computeIfAbsent(fm.roomId(), k -> new ArrayList<>()).add(fm);
			}
			for (String instId : fm.instructorIds()) {
				fixedByInstructor.computeIfAbsent(instId, k -> new ArrayList<>()).add(fm);
			}
		}

		Map<String, List<MeetingTime>> roomBookings = new HashMap<>();
		Map<String, List<MeetingTime>> equipBookings = new HashMap<>();
		for (ScheduledBooking b : input.approvedBookings()) {
			if (!b.isApproved()) {
				continue;
			}
			MeetingTime window = MeetingTime.oneTime(b.startTime().toLocalDate(),
					b.startTime().toLocalTime(), b.endTime().toLocalTime());
			if (b.resourceType() == ResourceType.ROOM) {
				roomBookings.computeIfAbsent(b.resourceId(), k -> new ArrayList<>()).add(window);
			} else if (b.resourceType() == ResourceType.EQUIPMENT) {
				equipBookings.computeIfAbsent(b.resourceId(), k -> new ArrayList<>()).add(window);
			}
		}

		Map<String, List<InstructorAvailabilityBlock>> unavailableByInst = new HashMap<>();
		Map<String, List<InstructorAvailabilityBlock>> preferByInst = new HashMap<>();
		for (InstructorAvailabilityBlock block : input.availabilities()) {
			if (block.isUnavailable()) {
				unavailableByInst.computeIfAbsent(block.instructorId(), k -> new ArrayList<>()).add(block);
			} else if (block.isPrefer()) {
				preferByInst.computeIfAbsent(block.instructorId(), k -> new ArrayList<>()).add(block);
			}
		}

		List<SectionDomain> domains = new ArrayList<>(requests.size());
		List<String> diagnostics = new ArrayList<>();

		for (SectionRequest req : requests) {
			List<SlotPattern> patterns = SlotPattern.standardPatterns(req.creditHours(), req.patternStyle());
			boolean catalogHasRequiredType = req.requiredRoomType() != null && input.rooms().stream()
					.anyMatch(r -> r.roomType() == req.requiredRoomType());
			List<RoomOption> eligibleRooms = input.rooms().stream()
					.filter(r -> isRoomEligible(req, r, catalogHasRequiredType))
					.toList();
			if (eligibleRooms.isEmpty()) {
				diagnostics.add(buildNoRoomDiagnostic(req));
			}

			List<InstructorOption> eligibleInstructors = new ArrayList<>();
			for (String candId : req.candidateInstructorIds()) {
				InstructorOption opt = instructorById.get(candId);
				if (opt != null) {
					eligibleInstructors.add(opt);
				}
			}
			if (eligibleInstructors.isEmpty()) {
				diagnostics.add("No eligible instructor found for " + req.courseCode() + ".");
			}

			boolean[][] forbiddenPatternRoom = new boolean[patterns.size()][eligibleRooms.size()];
			for (int k = 0; k < patterns.size(); k++) {
				SlotPattern pat = patterns.get(k);
				for (int j = 0; j < eligibleRooms.size(); j++) {
					forbiddenPatternRoom[k][j] = clashesWithRoom(pat, eligibleRooms.get(j).roomId(), fixedByRoom, roomBookings);
				}
			}

			boolean[][] forbiddenPatternInst = new boolean[patterns.size()][eligibleInstructors.size()];
			boolean[][] preferredPatternInst = new boolean[patterns.size()][eligibleInstructors.size()];
			for (int k = 0; k < patterns.size(); k++) {
				SlotPattern pat = patterns.get(k);
				for (int m = 0; m < eligibleInstructors.size(); m++) {
					String instId = eligibleInstructors.get(m).instructorId();
					forbiddenPatternInst[k][m] = clashesWithInstructor(pat, instId, fixedByInstructor, unavailableByInst);
					preferredPatternInst[k][m] = matchesPrefer(pat, preferByInst.getOrDefault(instId, List.of()));
				}
			}

			if (!eligibleRooms.isEmpty() && !eligibleInstructors.isEmpty()) {
				boolean hasViable = false;
				for (int k = 0; k < patterns.size() && !hasViable; k++) {
					boolean okRoom = false;
					for (int j = 0; j < eligibleRooms.size(); j++) {
						if (!forbiddenPatternRoom[k][j]) {
							okRoom = true;
							break;
						}
					}
					boolean okInst = false;
					for (int m = 0; m < eligibleInstructors.size(); m++) {
						if (!forbiddenPatternInst[k][m]) {
							okInst = true;
							break;
						}
					}
					if (okRoom && okInst) {
						hasViable = true;
					}
				}
				if (!hasViable) {
					diagnostics.add("No valid time slot satisfies both room availability and instructor availability for "
							+ req.courseCode() + " sec " + req.sectionNumber() + ".");
				}
			}

			domains.add(new SectionDomain(req, patterns, eligibleRooms, eligibleInstructors,
					forbiddenPatternRoom, forbiddenPatternInst, preferredPatternInst));
		}

		return new PreparedProblem(domains, equipBookings, fixedByInstructor, preferByInst, diagnostics);
	}

	private static boolean isRoomEligible(SectionRequest req, RoomOption room, boolean strictRoomType) {
		if (strictRoomType && req.requiredRoomType() != null && room.roomType() != req.requiredRoomType()) {
			return false;
		}
		if (req.enrolledStudents() > 0 && room.capacity() < req.enrolledStudents()) {
			return false;
		}
		if (!req.requiredFixedEquipment().isEmpty()) {
			Set<String> roomEquipLower = new HashSet<>();
			for (String item : room.fixedEquipment()) {
				roomEquipLower.add(item.trim().toLowerCase(Locale.ROOT));
			}
			for (String needed : req.requiredFixedEquipment()) {
				if (!roomEquipLower.contains(needed.trim().toLowerCase(Locale.ROOT))) {
					return false;
				}
			}
		}
		return true;
	}

	private static String buildNoRoomDiagnostic(SectionRequest req) {
		StringBuilder sb = new StringBuilder("No available ");
		sb.append(req.requiredRoomType() != null ? req.requiredRoomType() : "matching").append(" room fits ")
				.append(req.courseCode());
		if (req.enrolledStudents() > 0) {
			sb.append(" (enrollment ").append(req.enrolledStudents()).append(")");
		}
		if (!req.requiredFixedEquipment().isEmpty()) {
			sb.append(" with required equipment ").append(req.requiredFixedEquipment());
		}
		return sb.append(".").toString();
	}

	private static boolean clashesWithRoom(SlotPattern pat, String roomId,
			Map<String, List<ScheduledMeeting>> fixedByRoom, Map<String, List<MeetingTime>> roomBookings) {
		for (ScheduledMeeting fm : fixedByRoom.getOrDefault(roomId, List.of())) {
			for (MeetingTime mt : fm.meetings()) {
				if (pat.overlaps(mt)) {
					return true;
				}
			}
		}
		for (MeetingTime bk : roomBookings.getOrDefault(roomId, List.of())) {
			if (pat.overlaps(bk)) {
				return true;
			}
		}
		return false;
	}

	private static boolean clashesWithInstructor(SlotPattern pat, String instId,
			Map<String, List<ScheduledMeeting>> fixedByInstructor,
			Map<String, List<InstructorAvailabilityBlock>> unavailableByInst) {
		for (ScheduledMeeting fm : fixedByInstructor.getOrDefault(instId, List.of())) {
			for (MeetingTime mt : fm.meetings()) {
				if (pat.overlaps(mt)) {
					return true;
				}
			}
		}
		for (InstructorAvailabilityBlock block : unavailableByInst.getOrDefault(instId, List.of())) {
			if (pat.overlaps(MeetingTime.weekly(block.dayOfWeek(), block.startTime(), block.endTime()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean matchesPrefer(SlotPattern pat, List<InstructorAvailabilityBlock> preferBlocks) {
		if (preferBlocks.isEmpty()) {
			return false;
		}
		for (MeetingSlot slot : pat.meetings()) {
			boolean covered = false;
			for (InstructorAvailabilityBlock pref : preferBlocks) {
				if (pref.dayOfWeek() == slot.day()
						&& !slot.start().isBefore(pref.startTime())
						&& !slot.end().isAfter(pref.endTime())) {
					covered = true;
					break;
				}
			}
			if (!covered) {
				return false;
			}
		}
		return true;
	}

	// ---- Sat4j PB-MaxSAT Model & Solve ----

	private AutoScheduleResult solveWithBlocking(AutoScheduleInput input, PreparedProblem prep,
			boolean patternOnlyBlocking, long startedAt) {
		IPBSolver pbSolver = SolverFactory.newDefault();
		pbSolver.setTimeoutMs(Math.max(200L, input.options().timeoutMillis()));

		int n = prep.domains.size();
		int[][] pVar = new int[n][];
		int[][] rVar = new int[n][];
		int[][] iVar = new int[n][];
		for (int s = 0; s < n; s++) {
			SectionDomain d = prep.domains.get(s);
			pVar[s] = allocateVars(pbSolver, d.patterns.size());
			rVar[s] = allocateVars(pbSolver, d.rooms.size());
			iVar[s] = allocateVars(pbSolver, d.instructors.size());
		}

		Map<Integer, Integer> objWeights = new LinkedHashMap<>();
		try {
			addExactlyOneAndForbidden(pbSolver, prep.domains, pVar, rVar, iVar);
			addPairwiseResourceConflicts(pbSolver, prep.domains, pVar, rVar, iVar);
			addEquipmentConstraints(pbSolver, input, prep, pVar);
			addStudentGroupConstraints(pbSolver, input, prep, pVar);
			addBlockingClauses(pbSolver, input.excludedSolutions(), prep.domains, pVar, rVar, iVar, patternOnlyBlocking);
			addSoftObjectives(pbSolver, input, prep, pVar, rVar, iVar, objWeights);
		} catch (ContradictionException e) {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
			return new AutoScheduleResult(false, "UNSAT", List.of(),
					QualitySummary.empty(elapsedMs, pbSolver.nVars(), pbSolver.nConstraints()),
					List.of("No conflict-free schedule satisfies all hard constraints simultaneously."));
		}

		int varsCount = pbSolver.nVars();
		int clausesCount = pbSolver.nConstraints();
		int[] bestModel = null;
		boolean optimal = true;

		if (!objWeights.isEmpty()) {
			VecInt objVars = new VecInt(objWeights.size());
			Vec<BigInteger> objCoeffs = new Vec<>(objWeights.size());
			for (Map.Entry<Integer, Integer> entry : objWeights.entrySet()) {
				objVars.push(entry.getKey());
				objCoeffs.push(BigInteger.valueOf(entry.getValue()));
			}
			pbSolver.setObjectiveFunction(new ObjectiveFunction(objVars, objCoeffs));
			PseudoOptDecorator opt = new PseudoOptDecorator(pbSolver);
			try {
				while (opt.admitABetterSolution()) {
					bestModel = opt.model().clone();
					opt.discardCurrentSolution();
				}
			} catch (TimeoutException e) {
				optimal = false;
			} catch (ContradictionException e) {
				// Expected when discarding the final optimal solution yields UNSAT.
			}
		} else {
			try {
				if (pbSolver.isSatisfiable()) {
					bestModel = pbSolver.model().clone();
				}
			} catch (TimeoutException e) {
				optimal = false;
			}
		}

		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
		if (bestModel == null) {
			String msg = optimal
					? "No conflict-free schedule satisfies all constraints together."
					: "Solver timed out before finding a feasible schedule.";
			return new AutoScheduleResult(false, "UNSAT", List.of(),
					QualitySummary.empty(elapsedMs, varsCount, clausesCount), List.of(msg));
		}

		return extractResult(input, prep, pVar, rVar, iVar, bestModel, objWeights, optimal, elapsedMs, varsCount, clausesCount);
	}

	private static int[] allocateVars(IPBSolver solver, int count) {
		int[] vars = new int[count];
		for (int i = 0; i < count; i++) {
			vars[i] = solver.nextFreeVarId(true);
		}
		return vars;
	}

	private static void addExactlyOneAndForbidden(IPBSolver solver, List<SectionDomain> domains,
			int[][] pVar, int[][] rVar, int[][] iVar) throws ContradictionException {
		for (int s = 0; s < domains.size(); s++) {
			SectionDomain d = domains.get(s);
			solver.addExactly(new VecInt(pVar[s]), 1);
			solver.addExactly(new VecInt(rVar[s]), 1);
			solver.addExactly(new VecInt(iVar[s]), 1);
			for (int k = 0; k < d.patterns.size(); k++) {
				for (int j = 0; j < d.rooms.size(); j++) {
					if (d.forbiddenPatternRoom[k][j]) {
						solver.addClause(new VecInt(new int[] { -pVar[s][k], -rVar[s][j] }));
					}
				}
				for (int m = 0; m < d.instructors.size(); m++) {
					if (d.forbiddenPatternInst[k][m]) {
						solver.addClause(new VecInt(new int[] { -pVar[s][k], -iVar[s][m] }));
					}
				}
			}
		}
	}

	private static void addPairwiseResourceConflicts(IPBSolver solver, List<SectionDomain> domains,
			int[][] pVar, int[][] rVar, int[][] iVar) throws ContradictionException {
		int n = domains.size();
		for (int s1 = 0; s1 < n; s1++) {
			SectionDomain d1 = domains.get(s1);
			for (int s2 = s1 + 1; s2 < n; s2++) {
				SectionDomain d2 = domains.get(s2);
				List<int[]> overlappingPatterns = new ArrayList<>();
				for (int k1 = 0; k1 < d1.patterns.size(); k1++) {
					for (int k2 = 0; k2 < d2.patterns.size(); k2++) {
						if (d1.patterns.get(k1).overlaps(d2.patterns.get(k2))) {
							overlappingPatterns.add(new int[] { k1, k2 });
						}
					}
				}
				if (overlappingPatterns.isEmpty()) {
					continue;
				}
				// Shared rooms
				for (int j1 = 0; j1 < d1.rooms.size(); j1++) {
					String roomId = d1.rooms.get(j1).roomId();
					for (int j2 = 0; j2 < d2.rooms.size(); j2++) {
						if (roomId.equals(d2.rooms.get(j2).roomId())) {
							for (int[] pair : overlappingPatterns) {
								solver.addClause(new VecInt(new int[] {
										-pVar[s1][pair[0]], -pVar[s2][pair[1]], -rVar[s1][j1], -rVar[s2][j2]
								}));
							}
						}
					}
				}
				// Shared instructors
				for (int m1 = 0; m1 < d1.instructors.size(); m1++) {
					String instId = d1.instructors.get(m1).instructorId();
					for (int m2 = 0; m2 < d2.instructors.size(); m2++) {
						if (instId.equals(d2.instructors.get(m2).instructorId())) {
							for (int[] pair : overlappingPatterns) {
								solver.addClause(new VecInt(new int[] {
										-pVar[s1][pair[0]], -pVar[s2][pair[1]], -iVar[s1][m1], -iVar[s2][m2]
								}));
							}
						}
					}
				}
			}
		}
	}

	private static void addEquipmentConstraints(IPBSolver solver, AutoScheduleInput input,
			PreparedProblem prep, int[][] pVar) throws ContradictionException {
		Set<String> neededIds = new LinkedHashSet<>();
		for (SectionDomain d : prep.domains) {
			for (EquipmentNeed need : d.request.portableEquipment()) {
				if (need.units() > 0) {
					neededIds.add(need.equipmentId());
				}
			}
		}
		if (neededIds.isEmpty()) {
			return;
		}

		// Collect all distinct weekly meeting intervals across all candidate patterns
		Set<MeetingTime> timePoints = new LinkedHashSet<>();
		for (SectionDomain d : prep.domains) {
			for (SlotPattern pat : d.patterns) {
				for (MeetingSlot slot : pat.meetings()) {
					timePoints.add(MeetingTime.fromSlot(slot));
				}
			}
		}

		for (String eqId : neededIds) {
			EquipmentInfo info = input.equipmentCatalog().get(eqId);
			int totalUnits = info != null ? info.unitCount() : 0;
			List<MeetingTime> bookingsForEq = prep.equipBookings.getOrDefault(eqId, List.of());

			for (MeetingTime point : timePoints) {
				int fixedUsed = 0;
				for (ScheduledMeeting fm : input.fixedSections()) {
					int units = fm.equipmentNeeds().getOrDefault(eqId, 0);
					if (units <= 0 && info != null) {
						units = fm.equipmentNeeds().getOrDefault(info.name(), 0);
					}
					if (units > 0 && fm.meetings().stream().anyMatch(point::overlaps)) {
						fixedUsed += units;
					}
				}
				for (MeetingTime bk : bookingsForEq) {
					if (point.overlaps(bk)) {
						fixedUsed++;
					}
				}

				int available = totalUnits - fixedUsed;
				List<Integer> vars = new ArrayList<>();
				List<Integer> coeffs = new ArrayList<>();
				int maxPossible = 0;

				for (int s = 0; s < prep.domains.size(); s++) {
					SectionDomain d = prep.domains.get(s);
					int reqUnits = requestedUnits(d.request, eqId, info);
					if (reqUnits <= 0) {
						continue;
					}
					int sectionAdded = 0;
					for (int k = 0; k < d.patterns.size(); k++) {
						if (d.patterns.get(k).overlaps(point)) {
							vars.add(pVar[s][k]);
							coeffs.add(reqUnits);
							sectionAdded = reqUnits;
						}
					}
					maxPossible += sectionAdded;
				}

				if (vars.isEmpty()) {
					continue;
				}
				if (available <= 0) {
					for (int v : vars) {
						solver.addClause(new VecInt(new int[] { -v }));
					}
				} else if (maxPossible > available) {
					VecInt vecV = new VecInt(vars.size());
					VecInt vecC = new VecInt(coeffs.size());
					for (int idx = 0; idx < vars.size(); idx++) {
						vecV.push(vars.get(idx));
						vecC.push(coeffs.get(idx));
					}
					solver.addAtMost(vecV, vecC, available);
				}
			}
		}
	}

	private static int requestedUnits(SectionRequest req, String eqId, EquipmentInfo info) {
		for (EquipmentNeed need : req.portableEquipment()) {
			if (need.equipmentId().equals(eqId) || (info != null && need.equipmentId().equals(info.name()))) {
				return need.units();
			}
		}
		return 0;
	}

	private static void addStudentGroupConstraints(IPBSolver solver, AutoScheduleInput input,
			PreparedProblem prep, int[][] pVar) throws ContradictionException {
		ConflictRules.SharedStudentsLookup lookup = input.sharedStudentsLookup();
		if (lookup == null) {
			return;
		}
		int n = prep.domains.size();
		for (int s1 = 0; s1 < n; s1++) {
			SectionDomain d1 = prep.domains.get(s1);
			for (int s2 = s1 + 1; s2 < n; s2++) {
				SectionDomain d2 = prep.domains.get(s2);
				int shared = Math.max(
						lookup.getSharedStudents(d1.request.courseCode(), d2.request.courseCode()),
						lookup.getSharedStudents(d1.request.subjectId(), d2.request.subjectId()));
				if (shared >= ConflictRules.DEFAULT_STUDENT_GROUP_THRESHOLD) {
					for (int k1 = 0; k1 < d1.patterns.size(); k1++) {
						for (int k2 = 0; k2 < d2.patterns.size(); k2++) {
							if (d1.patterns.get(k1).overlaps(d2.patterns.get(k2))) {
								solver.addClause(new VecInt(new int[] { -pVar[s1][k1], -pVar[s2][k2] }));
							}
						}
					}
				}
			}
			for (ScheduledMeeting fm : input.fixedSections()) {
				int shared = Math.max(
						lookup.getSharedStudents(d1.request.courseCode(), fm.courseCode()),
						lookup.getSharedStudents(d1.request.subjectId(), fm.sectionId()));
				if (shared >= ConflictRules.DEFAULT_STUDENT_GROUP_THRESHOLD) {
					for (int k = 0; k < d1.patterns.size(); k++) {
						SlotPattern pat = d1.patterns.get(k);
						if (fm.meetings().stream().anyMatch(pat::overlaps)) {
							solver.addClause(new VecInt(new int[] { -pVar[s1][k] }));
						}
					}
				}
			}
		}
	}

	private static void addBlockingClauses(IPBSolver solver, List<PreviousSolution> excluded,
			List<SectionDomain> domains, int[][] pVar, int[][] rVar, int[][] iVar,
			boolean patternOnly) throws ContradictionException {
		for (PreviousSolution prev : excluded) {
			VecInt clause = new VecInt();
			for (PlacementKey pk : prev.placements()) {
				for (int s = 0; s < domains.size(); s++) {
					SectionDomain d = domains.get(s);
					if (!d.request.subjectId().equals(pk.subjectId())
							|| !d.request.sectionNumber().equals(pk.sectionNumber())) {
						continue;
					}
					int pIdx = indexOfPattern(d.patterns, pk.patternId());
					if (pIdx >= 0) {
						clause.push(-pVar[s][pIdx]);
					}
					if (!patternOnly) {
						int rIdx = indexOfRoom(d.rooms, pk.roomId());
						if (rIdx >= 0) {
							clause.push(-rVar[s][rIdx]);
						}
						int iIdx = indexOfInstructor(d.instructors, pk.instructorId());
						if (iIdx >= 0) {
							clause.push(-iVar[s][iIdx]);
						}
					}
				}
			}
			if (!clause.isEmpty()) {
				solver.addClause(clause);
			}
		}
	}

	private static void addSoftObjectives(IPBSolver solver, AutoScheduleInput input, PreparedProblem prep,
			int[][] pVar, int[][] rVar, int[][] iVar, Map<Integer, Integer> objWeights)
			throws ContradictionException {
		SolverOptions opts = input.options();
		List<SectionDomain> domains = prep.domains;

		// S2: Prefer prime daytime hours (09:00-17:00)
		if (opts.preferPrimeHours()) {
			for (int s = 0; s < domains.size(); s++) {
				SectionDomain d = domains.get(s);
				for (int k = 0; k < d.patterns.size(); k++) {
					if (d.patterns.get(k).edgeHours()) {
						addWeight(objWeights, pVar[s][k], EDGE_HOURS_PENALTY);
					}
				}
			}
		}

		// S3: Reward PREFER availability blocks (penalize non-preferred when instructor has PREFER blocks)
		if (opts.respectPreferences()) {
			for (int s = 0; s < domains.size(); s++) {
				SectionDomain d = domains.get(s);
				boolean anyHasPrefer = d.instructors.stream()
						.anyMatch(inst -> !prep.preferByInst.getOrDefault(inst.instructorId(), List.of()).isEmpty());
				if (!anyHasPrefer) {
					continue;
				}
				int nonPrefVar = solver.nextFreeVarId(true);
				addWeight(objWeights, nonPrefVar, MISSED_PREFER_PENALTY);
				for (int k = 0; k < d.patterns.size(); k++) {
					for (int m = 0; m < d.instructors.size(); m++) {
						if (!d.preferredPatternInst[k][m]) {
							solver.addClause(new VecInt(new int[] { -pVar[s][k], -iVar[s][m], nonPrefVar }));
						}
					}
				}
			}
		}

		// S4: Optimize room capacity fit
		if (opts.optimizeRoomFit()) {
			for (int s = 0; s < domains.size(); s++) {
				SectionDomain d = domains.get(s);
				int enrolled = d.request.enrolledStudents();
				if (enrolled <= 0) {
					continue;
				}
				for (int j = 0; j < d.rooms.size(); j++) {
					double fill = (double) enrolled / Math.max(1, d.rooms.get(j).capacity());
					if (fill < 0.40) {
						addWeight(objWeights, rVar[s][j], UNDERFILLED_ROOM_PENALTY);
					} else if (fill < 0.60) {
						addWeight(objWeights, rVar[s][j], MODERATE_FILL_PENALTY);
					}
				}
			}
		}

		// S1: Avoid split days (e.g. 09:00 + 18:00) & marathon fatigue
		if (opts.avoidSplitDays()) {
			addInstructorSplitDayPenalties(solver, prep, pVar, iVar, objWeights);
			addDepartmentSplitDayPenalties(solver, input.fixedSections(), domains, pVar, objWeights);
		}
	}

	private static void addInstructorSplitDayPenalties(IPBSolver solver, PreparedProblem prep,
			int[][] pVar, int[][] iVar, Map<Integer, Integer> objWeights) throws ContradictionException {
		Set<String> allInstIds = new LinkedHashSet<>();
		for (SectionDomain d : prep.domains) {
			for (InstructorOption opt : d.instructors) {
				allInstIds.add(opt.instructorId());
			}
		}
		for (String instId : allInstIds) {
			Map<DayOfWeek, int[]> busyByDay = new HashMap<>();
			for (DayOfWeek day : WEEKDAYS) {
				busyByDay.put(day, allocateVars(solver, SlotPattern.BAND_COUNT));
			}
			for (ScheduledMeeting fm : prep.fixedByInstructor.getOrDefault(instId, List.of())) {
				for (SlotPattern.DayBand db : SlotPattern.occupiedBandsFor(fm.meetings())) {
					int[] dayVars = busyByDay.get(db.day());
					if (dayVars != null) {
						solver.addClause(new VecInt(new int[] { dayVars[db.band()] }));
					}
				}
			}
			for (int s = 0; s < prep.domains.size(); s++) {
				SectionDomain d = prep.domains.get(s);
				int m = indexOfInstructor(d.instructors, instId);
				if (m < 0) {
					continue;
				}
				for (int k = 0; k < d.patterns.size(); k++) {
					for (SlotPattern.DayBand db : d.patterns.get(k).occupiedBands()) {
						int[] dayVars = busyByDay.get(db.day());
						if (dayVars != null) {
							solver.addClause(new VecInt(new int[] { -pVar[s][k], -iVar[s][m], dayVars[db.band()] }));
						}
					}
				}
			}
			for (DayOfWeek day : WEEKDAYS) {
				addBandSpanPenalties(solver, busyByDay.get(day), SPLIT_DAY_PENALTY, WIDE_SPAN_PENALTY, MARATHON_PENALTY, objWeights);
			}
		}
	}

	private static void addDepartmentSplitDayPenalties(IPBSolver solver, List<ScheduledMeeting> fixedSections,
			List<SectionDomain> domains, int[][] pVar, Map<Integer, Integer> objWeights) throws ContradictionException {
		Map<String, List<Integer>> byDept = new LinkedHashMap<>();
		for (int s = 0; s < domains.size(); s++) {
			String dept = domains.get(s).request.department();
			if (dept != null && !dept.isBlank()) {
				byDept.computeIfAbsent(dept.trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
			}
		}
		for (Map.Entry<String, List<Integer>> entry : byDept.entrySet()) {
			if (entry.getValue().size() < 2) {
				continue;
			}
			Map<DayOfWeek, int[]> busyByDay = new HashMap<>();
			for (DayOfWeek day : WEEKDAYS) {
				busyByDay.put(day, allocateVars(solver, SlotPattern.BAND_COUNT));
			}
			for (int s : entry.getValue()) {
				SectionDomain d = domains.get(s);
				for (int k = 0; k < d.patterns.size(); k++) {
					for (SlotPattern.DayBand db : d.patterns.get(k).occupiedBands()) {
						int[] dayVars = busyByDay.get(db.day());
						if (dayVars != null) {
							solver.addClause(new VecInt(new int[] { -pVar[s][k], dayVars[db.band()] }));
						}
					}
				}
			}
			for (DayOfWeek day : WEEKDAYS) {
				addBandSpanPenalties(solver, busyByDay.get(day), DEPT_SPLIT_PENALTY, DEPT_SPAN_PENALTY, 0, objWeights);
			}
		}
	}

	private static void addBandSpanPenalties(IPBSolver solver, int[] bands, int farSplitWeight,
			int mediumSpanWeight, int marathonWeight, Map<Integer, Integer> objWeights) throws ContradictionException {
		for (int b1 = 0; b1 < SlotPattern.BAND_COUNT; b1++) {
			for (int b2 = b1 + 1; b2 < SlotPattern.BAND_COUNT; b2++) {
				int diff = b2 - b1;
				int weight = diff >= 4 ? farSplitWeight : (diff == 3 ? mediumSpanWeight : 0);
				if (weight > 0) {
					int penVar = solver.nextFreeVarId(true);
					solver.addClause(new VecInt(new int[] { -bands[b1], -bands[b2], penVar }));
					addWeight(objWeights, penVar, weight);
				}
			}
		}
		if (marathonWeight > 0) {
			for (int b = 0; b <= SlotPattern.BAND_COUNT - 4; b++) {
				int fatVar = solver.nextFreeVarId(true);
				solver.addClause(new VecInt(new int[] { -bands[b], -bands[b + 1], -bands[b + 2], -bands[b + 3], fatVar }));
				addWeight(objWeights, fatVar, marathonWeight);
			}
		}
	}

	private static void addWeight(Map<Integer, Integer> objWeights, int var, int weight) {
		objWeights.merge(var, weight, Integer::sum);
	}

	// ---- Result Extraction & Quality Metrics ----

	private static AutoScheduleResult extractResult(AutoScheduleInput input, PreparedProblem prep,
			int[][] pVar, int[][] rVar, int[][] iVar, int[] model, Map<Integer, Integer> objWeights,
			boolean optimal, long elapsedMs, int varsCount, int clausesCount) {
		Set<Integer> trueVars = new HashSet<>(model.length);
		for (int lit : model) {
			if (lit > 0) {
				trueVars.add(lit);
			}
		}

		List<ProposedPlacement> placements = new ArrayList<>(prep.domains.size());
		Map<String, Map<DayOfWeek, List<MeetingTime>>> instDailyMeetings = new HashMap<>();
		for (ScheduledMeeting fm : input.fixedSections()) {
			for (String instId : fm.instructorIds()) {
				for (MeetingTime mt : fm.meetings()) {
					if (mt.dayOfWeek() != null) {
						instDailyMeetings.computeIfAbsent(instId, k -> new HashMap<>())
								.computeIfAbsent(mt.dayOfWeek(), k -> new ArrayList<>()).add(mt);
					}
				}
			}
		}

		int preferredMatched = 0;
		int edgeHourSections = 0;

		for (int s = 0; s < prep.domains.size(); s++) {
			SectionDomain d = prep.domains.get(s);
			int k = firstTrueIndex(pVar[s], trueVars);
			int j = firstTrueIndex(rVar[s], trueVars);
			int m = firstTrueIndex(iVar[s], trueVars);

			SlotPattern pat = d.patterns.get(k);
			RoomOption room = d.rooms.get(j);
			InstructorOption inst = d.instructors.get(m);

			boolean matchedPref = d.preferredPatternInst[k][m];
			if (matchedPref) {
				preferredMatched++;
			}
			if (pat.edgeHours()) {
				edgeHourSections++;
			}

			for (MeetingSlot slot : pat.meetings()) {
				if (slot.day() != null) {
					instDailyMeetings.computeIfAbsent(inst.instructorId(), x -> new HashMap<>())
							.computeIfAbsent(slot.day(), x -> new ArrayList<>())
							.add(MeetingTime.fromSlot(slot));
				}
			}

			int fillPercent = d.request.enrolledStudents() > 0 && room.capacity() > 0
					? (int) Math.round((d.request.enrolledStudents() * 100.0) / room.capacity())
					: 0;

			placements.add(new ProposedPlacement(
					d.request.subjectId(),
					d.request.courseCode(),
					d.request.subjectTitle(),
					d.request.sectionNumber(),
					room.roomId(),
					room.name(),
					room.capacity(),
					inst.instructorId(),
					inst.name(),
					pat.id(),
					pat.label(),
					pat.meetings(),
					d.request.portableEquipment(),
					matchedPref,
					fillPercent
			));
		}

		int splitDayCount = 0;
		int maxSpanMinutes = 0;
		for (Map<DayOfWeek, List<MeetingTime>> byDay : instDailyMeetings.values()) {
			for (List<MeetingTime> dayList : byDay.values()) {
				if (dayList.size() < 2) {
					continue;
				}
				LocalTime minStart = dayList.stream().map(MeetingTime::start).min(LocalTime::compareTo).orElseThrow();
				LocalTime maxEnd = dayList.stream().map(MeetingTime::end).max(LocalTime::compareTo).orElseThrow();
				int spanMinutes = (int) Duration.between(minStart, maxEnd).toMinutes();
				maxSpanMinutes = Math.max(maxSpanMinutes, spanMinutes);
				if (spanMinutes > SPLIT_SPAN_THRESHOLD_MINUTES) {
					splitDayCount++;
				}
			}
		}
		if (maxSpanMinutes == 0 && !placements.isEmpty()) {
			for (Map<DayOfWeek, List<MeetingTime>> byDay : instDailyMeetings.values()) {
				for (List<MeetingTime> dayList : byDay.values()) {
					LocalTime minStart = dayList.stream().map(MeetingTime::start).min(LocalTime::compareTo).orElseThrow();
					LocalTime maxEnd = dayList.stream().map(MeetingTime::end).max(LocalTime::compareTo).orElseThrow();
					maxSpanMinutes = Math.max(maxSpanMinutes, (int) Duration.between(minStart, maxEnd).toMinutes());
				}
			}
		}

		int solverPenalty = 0;
		for (Map.Entry<Integer, Integer> entry : objWeights.entrySet()) {
			if (trueVars.contains(entry.getKey())) {
				solverPenalty += entry.getValue();
			}
		}

		int basePenalty = splitDayCount * 20 + edgeHourSections * 8
				+ (int) Math.max(0, placements.size() - preferredMatched) * 2;
		int convenienceScore = Math.max(0, Math.min(100, 100 - Math.min(85, basePenalty + solverPenalty / 3)));

		QualitySummary summary = new QualitySummary(
				placements.size(),
				preferredMatched,
				edgeHourSections,
				splitDayCount,
				(int) Math.round(maxSpanMinutes / 60.0),
				convenienceScore,
				solverPenalty,
				elapsedMs,
				varsCount,
				clausesCount
		);
		return new AutoScheduleResult(true, optimal ? "OPTIMAL" : "FEASIBLE", placements, summary, List.of());
	}

	private static int firstTrueIndex(int[] vars, Set<Integer> trueVars) {
		for (int i = 0; i < vars.length; i++) {
			if (trueVars.contains(vars[i])) {
				return i;
			}
		}
		throw new IllegalStateException("SAT model violated Exactly-One constraint.");
	}

	private static int indexOfPattern(List<SlotPattern> patterns, String patternId) {
		if (patternId == null) {
			return -1;
		}
		for (int i = 0; i < patterns.size(); i++) {
			if (patterns.get(i).id().equals(patternId)) {
				return i;
			}
		}
		return -1;
	}

	private static int indexOfRoom(List<RoomOption> rooms, String roomId) {
		if (roomId == null) {
			return -1;
		}
		for (int i = 0; i < rooms.size(); i++) {
			if (rooms.get(i).roomId().equals(roomId)) {
				return i;
			}
		}
		return -1;
	}

	private static int indexOfInstructor(List<InstructorOption> instructors, String instId) {
		if (instId == null) {
			return -1;
		}
		for (int i = 0; i < instructors.size(); i++) {
			if (instructors.get(i).instructorId().equals(instId)) {
				return i;
			}
		}
		return -1;
	}

	private record SectionDomain(
			SectionRequest request,
			List<SlotPattern> patterns,
			List<RoomOption> rooms,
			List<InstructorOption> instructors,
			boolean[][] forbiddenPatternRoom,
			boolean[][] forbiddenPatternInst,
			boolean[][] preferredPatternInst
	) {
	}

	private record PreparedProblem(
			List<SectionDomain> domains,
			Map<String, List<MeetingTime>> equipBookings,
			Map<String, List<ScheduledMeeting>> fixedByInstructor,
			Map<String, List<InstructorAvailabilityBlock>> preferByInst,
			List<String> diagnostics
	) {
	}
}
