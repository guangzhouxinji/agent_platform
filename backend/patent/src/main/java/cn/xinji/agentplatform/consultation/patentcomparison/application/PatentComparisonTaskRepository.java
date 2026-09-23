package cn.xinji.agentplatform.consultation.patentcomparison.application;

import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonTask;
import java.util.List;
import java.util.Optional;

public interface PatentComparisonTaskRepository {
    Optional<PatentComparisonTask> findById(String taskId);
    Optional<PatentComparisonTask> findByIdempotency(String requester, String key);
    List<PatentComparisonTask> findByRequester(String requester);
    PatentComparisonTask saveIfAbsent(PatentComparisonTask task);
}
