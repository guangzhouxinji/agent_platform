package cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.memory;

import cn.xinji.agentplatform.consultation.trademarkcomparison.application.TrademarkComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.trademarkcomparison.domain.TrademarkComparisonTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTrademarkComparisonTaskRepository implements TrademarkComparisonTaskRepository {
    private final Map<String, TrademarkComparisonTask> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byKey = new ConcurrentHashMap<>();
    @Override public Optional<TrademarkComparisonTask> findById(String taskId) { return Optional.ofNullable(byId.get(taskId)); }
    @Override public Optional<TrademarkComparisonTask> findByIdempotency(String requester, String key) { String id=byKey.get(requester+"|"+key);return id==null?Optional.empty():findById(id); }
    @Override public List<TrademarkComparisonTask> findByRequester(String requester) { return byId.values().stream().filter(task->task.requester().equals(requester)).sorted(Comparator.comparing(TrademarkComparisonTask::createdAt).reversed().thenComparing(TrademarkComparisonTask::taskId)).toList(); }
    @Override public synchronized TrademarkComparisonTask saveIfAbsent(TrademarkComparisonTask task) { String key=task.requester()+"|"+task.idempotencyKey();String existing=byKey.get(key);if(existing!=null)return byId.get(existing);byId.put(task.taskId(),task);byKey.put(key,task.taskId());return task; }
}
