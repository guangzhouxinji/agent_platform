package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.util.List;

public interface DevHumanAcceptanceStore {
    record Pending(String taskId, String requestId, long requestSequence, long inputRevision, Instant createdAt) { }
    record Accepted(String taskId, String requestId, long requestSequence, long inputRevision,
                    String acceptedBy, Instant acceptedAt, boolean currentInput) { }
    interface SourceGuard { DevHumanRequestStore.Source permitted(Actor human, Instant now); }

    List<Pending> pending(Actor human, Guard guard, SourceGuard source);
    Accepted accept(Actor human, String taskId, long sequence, String key, long expectedTaskRevision,
                    long expectedRequestSequence, String requestId, Guard guard, SourceGuard source);
}
