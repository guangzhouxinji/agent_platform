package cn.xinji.agentplatform.consultation.casesearch.application;

import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchTask;
import java.util.List;
import java.util.Optional;

public interface CaseSearchTaskRepository {
    Optional<CaseSearchTask> findById(String taskId);
    Optional<CaseSearchTask> findByIdempotency(String requester, String key);
    List<CaseSearchTask> findByRequester(String requester);
    CaseSearchTask saveIfAbsent(CaseSearchTask task);
}
