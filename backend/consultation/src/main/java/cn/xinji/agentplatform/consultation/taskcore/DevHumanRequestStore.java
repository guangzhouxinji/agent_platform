package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;

public interface DevHumanRequestStore {
    record Source(String manifestId, String manifestDigest, String textDigest,
                  long withdrawalVersion, String text) { }
    record Request(String taskId, String requestId, long requestSequence, long inputRevision,
                   String requesterRef, String requestInputId, String requestText, Instant createdAt,
                   boolean currentInput, String taskState, String acceptanceState,
                   String acceptedBy, Instant acceptedAt) { }
    record Current(long requestSequence, Request request) { }

    interface SourceGuard {
        Source permitted(Actor human, boolean save, boolean display, Instant now);
    }

    Request create(Actor human, String taskId, String key, long expectedTaskRevision,
                   long expectedRequestSequence, String requestInputId, Guard guard, SourceGuard source);
    Current current(Actor human, String taskId, Guard guard, SourceGuard source);
    Request read(Actor human, String taskId, long sequence, Guard guard, SourceGuard source);
}
