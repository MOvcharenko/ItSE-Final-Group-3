package service;

import java.util.List;

import api.dto.AutoScheduleApplyRequest;
import api.dto.AutoScheduleRequest;
import service.view.AutoScheduleView;
import service.view.SectionView;

/**
 * SAT/MaxSAT automated schedule builder for Draft terms (owner: Dev B).
 */
public interface AutoScheduleService {

	AutoScheduleView preview(String termId, AutoScheduleRequest request);

	List<SectionView> apply(String termId, AutoScheduleApplyRequest request);
}
