package cn.xinji.agentplatform.consultation.patentsearch.application;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchTask;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.time.Instant;

public interface PatentSearchTaskRepository {
    Optional<PatentSearchTask> findById(String taskId);
    Optional<PatentSearchTask> findByIdempotency(String requester, String key);
    List<PatentSearchTask> findByRequester(String requester);
    PatentSearchTask saveIfAbsent(PatentSearchTask task);
    PatentSearchTask update(String taskId, UnaryOperator<PatentSearchTask> operation);
    PatentSearchTask requeueFailed(String taskId, Instant now);
    int failInterruptedTasks(Instant now);

    final class StateConflictException extends RuntimeException {}
}
