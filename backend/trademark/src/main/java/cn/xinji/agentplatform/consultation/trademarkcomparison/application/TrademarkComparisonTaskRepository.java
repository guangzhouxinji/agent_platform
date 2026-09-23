package cn.xinji.agentplatform.consultation.trademarkcomparison.application;

import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import java.util.List;
import java.util.Optional;

public interface TrademarkComparisonTaskRepository {
    Optional<TrademarkComparisonTask> findById(String taskId);
    Optional<TrademarkComparisonTask> findByIdempotency(String requester, String key);
    List<TrademarkComparisonTask> findByRequester(String requester);
    TrademarkComparisonTask saveIfAbsent(TrademarkComparisonTask task);
}
