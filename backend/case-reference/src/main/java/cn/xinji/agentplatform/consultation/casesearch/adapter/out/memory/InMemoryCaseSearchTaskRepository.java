package cn.xinji.agentplatform.consultation.casesearch.adapter.out.memory;

import cn.xinji.agentplatform.consultation.casesearch.application.CaseSearchTaskRepository;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchTask;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "case-search.storage", havingValue = "memory", matchIfMissing = true)
public final class InMemoryCaseSearchTaskRepository implements CaseSearchTaskRepository {
    private final Map<String, CaseSearchTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    @Override public Optional<CaseSearchTask> findById(String taskId) { return Optional.ofNullable(tasks.get(taskId)); }
    @Override public Optional<CaseSearchTask> findByIdempotency(String requester, String key) {
        return Optional.ofNullable(idempotency.get(requester + "\n" + key)).map(tasks::get);
    }
    @Override public List<CaseSearchTask> findByRequester(String requester) {
        return tasks.values().stream().filter(task -> task.requester().equals(requester))
                .sorted(Comparator.comparing(CaseSearchTask::createdAt).reversed().thenComparing(CaseSearchTask::taskId)).toList();
    }
    @Override public synchronized CaseSearchTask saveIfAbsent(CaseSearchTask task) {
        String index = task.requester() + "\n" + task.idempotencyKey();
        String existingId = idempotency.get(index);
        if (existingId != null) return tasks.get(existingId);
        tasks.put(task.taskId(), task);
        idempotency.put(index, task.taskId());
        return task;
    }
    public void clear() { tasks.clear(); idempotency.clear(); }
}
