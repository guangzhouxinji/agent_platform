package cn.xinji.agentplatform.consultation.trademarksearch.application;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.function.UnaryOperator;

public interface TrademarkSearchTaskRepository {
    Optional<TrademarkSearchTask> findById(String taskId);
    Optional<TrademarkSearchTask> findByIdempotency(String requester, String key);
    List<TrademarkSearchTask> findByRequester(String requester);
    void save(TrademarkSearchTask task);
    TrademarkSearchTask update(String taskId, UnaryOperator<TrademarkSearchTask> operation);
    int failInterruptedTasks(Instant now);
}
