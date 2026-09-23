package cn.xinji.agentplatform.adjudication.application;

import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import java.util.Optional;

public interface AdjudicationStore {
    Optional<StoredResult> findByIdempotency(String actorRef, String operation, String key);
    Optional<StoredWork> findByWorkItemId(String workItemId);
    AdjudicationSnapshot save(String actorRef, String operation, String key, String fingerprint,
            long expectedPersistenceVersion, AdjudicationSnapshot snapshot);
    record StoredResult(String fingerprint, AdjudicationSnapshot snapshot) {}
    record StoredWork(String ownerRef, long persistenceVersion, AdjudicationSnapshot snapshot) {}
    final class IdempotencyConflict extends RuntimeException {}
    final class VersionConflict extends RuntimeException {}
}
