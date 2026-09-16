package cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.memory;

import cn.xinji.agentplatform.consultation.patentcomparison.application.PatentComparisonTaskRepository;
import cn.xinji.agentplatform.consultation.patentcomparison.domain.PatentComparisonTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryPatentComparisonTaskRepository implements PatentComparisonTaskRepository {
    private final Map<String,PatentComparisonTask> byId=new ConcurrentHashMap<>();
    private final Map<String,String> byKey=new ConcurrentHashMap<>();
    @Override public Optional<PatentComparisonTask> findById(String id){return Optional.ofNullable(byId.get(id));}
    @Override public Optional<PatentComparisonTask> findByIdempotency(String requester,String key){String id=byKey.get(requester+"|"+key);return id==null?Optional.empty():findById(id);}
    @Override public List<PatentComparisonTask> findByRequester(String requester){return byId.values().stream().filter(t->t.requester().equals(requester)).sorted(Comparator.comparing(PatentComparisonTask::createdAt).reversed().thenComparing(PatentComparisonTask::taskId)).toList();}
    @Override public synchronized void save(PatentComparisonTask task){String key=task.requester()+"|"+task.idempotencyKey();if(byKey.containsKey(key))throw new IllegalStateException("duplicate idempotency key");byId.put(task.taskId(),task);byKey.put(key,task.taskId());}
}
