package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;

public interface DevHumanReturnStore {
    record Returned(String taskId, String requestId, long requestSequence, long inputRevision,
                    String returnId, String returnedBy, Instant returnedAt, boolean currentInput) { }

    Returned submit(Actor human, String taskId, long sequence, String key, long expectedTaskRevision,
                    long expectedRequestSequence, String requestId, Guard guard,
                    DevHumanRequestStore.SourceGuard source);
    Returned read(Actor owner, String taskId, long sequence, Guard guard,
                  DevHumanRequestStore.SourceGuard source);
}
