package cn.xinji.agentplatform.consultation.taskcore;

import java.time.Instant;
import java.util.List;

public interface DevTextResultStore {
    record Source(String recordId, String version, String digest, String locator, String claimText) { }
    record Member(int ordinal, String recordId, String version, String digest, String locator,
                  boolean available, String claimText) { }
    record ResultSet(String setId, String taskId, long sequence, long inputRevision, String method,
                     int actualScope, List<Member> members, Instant expiresAt) { }
    record Focus(String taskId, long sequence, String setId, int ordinal, String recordId,
                 String sourceVersion, String contentDigest, long inputRevision, Instant expiresAt) { }
    record EvidencePreparation(String taskId, String setId, int ordinal, long inputRevision,
                               long targetMessageRevision, String targetText, boolean historicalInput,
                               String recordId, String sourceVersion, String contentDigest, String locator,
                               String sourceStatus, String claimText, String featureConfirmation,
                               String technicalRelation, List<String> missing) { }

    interface SourceGuard {
        Source permitted(DevTaskCoreStore.Actor human, String recordId, String version,
                         String digest, boolean query, boolean save, boolean display, Instant now);
    }

    ResultSet search(DevTaskCoreStore.Actor human, String taskId, String key, long expectedRevision,
                     long expectedSequence, DevTaskCoreStore.Guard guard, SourceGuard sources);
    ResultSet read(DevTaskCoreStore.Actor human, String taskId, String setId,
                   DevTaskCoreStore.Guard guard, SourceGuard sources);
    Focus focus(DevTaskCoreStore.Actor human, String taskId, String key, String setId, int ordinal,
                long expectedRevision, long expectedSequence, DevTaskCoreStore.Guard guard, SourceGuard sources);
    EvidencePreparation prepareEvidence(DevTaskCoreStore.Actor human, String taskId, String setId,
                                        int ordinal, DevTaskCoreStore.Guard guard, SourceGuard sources);
}
