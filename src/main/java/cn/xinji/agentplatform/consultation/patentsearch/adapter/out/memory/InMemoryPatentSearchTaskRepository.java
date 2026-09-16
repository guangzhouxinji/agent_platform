package cn.xinji.agentplatform.consultation.patentsearch.adapter.out.memory;

import cn.xinji.agentplatform.consultation.patentsearch.application.PatentSearchTaskRepository;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.time.Instant;

public final class InMemoryPatentSearchTaskRepository implements PatentSearchTaskRepository {
    private final ConcurrentHashMap<String, PatentSearchTask> tasks = new ConcurrentHashMap<>();

    @Override public Optional<PatentSearchTask> findById(String taskId) { return Optional.ofNullable(tasks.get(taskId)); }
    @Override public Optional<PatentSearchTask> findByIdempotency(String requester, String key) {
        return tasks.values().stream().filter(task -> task.requester().equals(requester) && task.idempotencyKey().equals(key)).findFirst();
    }
    @Override public List<PatentSearchTask> findByRequester(String requester) {
        return tasks.values().stream().filter(task -> task.requester().equals(requester))
                .sorted(Comparator.comparing(PatentSearchTask::createdAt).reversed()).toList();
    }
    @Override public void save(PatentSearchTask task) { tasks.put(task.taskId(), task); }
    @Override public PatentSearchTask update(String taskId, UnaryOperator<PatentSearchTask> operation) {
        PatentSearchTask updated = tasks.computeIfPresent(taskId, (ignored, current) -> operation.apply(current));
        if (updated == null) throw new IllegalArgumentException("task not found");
        return updated;
    }
    @Override public int failInterruptedTasks(Instant now) {
        int[] count = {0};
        tasks.replaceAll((ignored, task) -> {
            if (task.status() != PatentSearchTask.Status.QUEUED && task.status() != PatentSearchTask.Status.PROCESSING) return task;
            count[0]++;
            return task.failed("RESTART_INTERRUPTED", now);
        });
        return count[0];
    }

    public void clear() { tasks.clear(); }
}
