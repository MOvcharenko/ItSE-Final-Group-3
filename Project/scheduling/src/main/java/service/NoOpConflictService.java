package service;

import org.springframework.stereotype.Service;

/**
 * Stub that finds nothing, so the timetable can be built before detection exists.
 * TODO(Dev B): replace with the real implementation. Until then publishing never asks for confirmation.
 */
@Service
public class NoOpConflictService implements ConflictService {

	@Override
	public void recheck(String termId, Ref changed) {
	}

	@Override
	public int countOpenHigh(String termId) {
		return 0;
	}

}
