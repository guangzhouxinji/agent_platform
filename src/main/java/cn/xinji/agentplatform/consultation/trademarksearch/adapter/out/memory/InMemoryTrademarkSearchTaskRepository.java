package cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.memory;

import cn.xinji.agentplatform.consultation.trademarksearch.application.TrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchTask;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public final class InMemoryTrademarkSearchTaskRepository implements TrademarkSearchTaskRepository {
    private final ConcurrentHashMap<String, TrademarkSearchTask> tasks = new ConcurrentHashMap<>();
    @Override public Optional<TrademarkSearchTask> findById(String id) { return Optional.ofNullable(tasks.get(id)); }
    @Override public Optional<TrademarkSearchTask> findByIdempotency(String requester, String key) { return tasks.values().stream().filter(value -> value.requester().equals(requester) && value.idempotencyKey().equals(key)).findFirst(); }
    @Override public List<TrademarkSearchTask> findByRequester(String requester) { return tasks.values().stream().filter(value -> value.requester().equals(requester)).sorted(Comparator.comparing(TrademarkSearchTask::createdAt).reversed()).toList(); }
    @Override public void save(TrademarkSearchTask task) { tasks.put(task.taskId(), task); }
    @Override public TrademarkSearchTask update(String id, UnaryOperator<TrademarkSearchTask> operation) { TrademarkSearchTask value = tasks.computeIfPresent(id, (ignored, current) -> operation.apply(current)); if (value == null) throw new IllegalArgumentException("task not found"); return value; }
    @Override public int failInterruptedTasks(Instant now) { int[] count = {0}; tasks.replaceAll((id, task) -> { if (task.status() == TrademarkSearchTask.Status.QUEUED || task.status() == TrademarkSearchTask.Status.PROCESSING) { count[0]++; return task.failed("RESTART_INTERRUPTED", now); } return task; }); return count[0]; }
    public void clear() { tasks.clear(); }
}
