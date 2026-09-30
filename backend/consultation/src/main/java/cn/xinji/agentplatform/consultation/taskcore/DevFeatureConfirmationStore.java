package cn.xinji.agentplatform.consultation.taskcore;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.util.List;

public interface DevFeatureConfirmationStore {
    record Candidate(String id, String text, String digest, int byteStart, int byteEnd) { }
    record Source(String manifestId, String manifestDigest, String inputDigest, String inputFingerprint,
                  long withdrawalVersion, List<Candidate> candidates) { }
    record Confirmation(String taskId, long featureRevision, long inputRevision, String actorRef,
                        String receiptId, boolean historicalInput, List<Candidate> candidates) { }

    interface SourceGuard {
        Source permitted(Actor human, String manifestId, boolean display, Instant now);
    }

    Confirmation confirm(Actor human, String taskId, String key, long expectedTaskRevision,
                         long expectedFeatureRevision, String manifestId, List<String> featureIds,
                         Guard guard, SourceGuard sources);

    Confirmation read(Actor human, String taskId, long featureRevision, Guard guard, SourceGuard sources);
}
