package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.util.List;

public interface DevHumanOpinionStore {
    record Opinion(String taskId, String requestId, long requestSequence, long inputRevision,
                   String opinionId, String opinionInputId, String opinionText, String submittedBy,
                   Instant submittedAt, boolean currentInput) { }
    record Pending(String taskId, String requestId, long requestSequence, long inputRevision,
                   Instant acceptedAt) { }
    interface SourceGuard { DevHumanRequestStore.Source permitted(Actor human, boolean save, boolean display,
                                                                    Instant now); }

    Opinion submit(Actor human, String taskId, long sequence, String key, long expectedTaskRevision,
                   long expectedRequestSequence, String requestId, String opinionInputId,
                   Guard guard, SourceGuard source);
    Opinion read(Actor owner, String taskId, long sequence, Guard guard, SourceGuard source);
    List<Pending> mine(Actor human, Guard guard, DevHumanRequestStore.SourceGuard requestSource,
                       SourceGuard opinionSource);
}
