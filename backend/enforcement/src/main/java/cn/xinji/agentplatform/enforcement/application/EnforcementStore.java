package cn.xinji.agentplatform.enforcement.application;

import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import java.util.Optional;

public interface EnforcementStore {
    Optional<StoredResult> findByIdempotency(String actorRef, String operation, String key);
    Optional<StoredWork> findByWorkItemId(String workItemId);
    EnforcementSnapshot save(String actorRef, String operation, String key, String fingerprint, EnforcementSnapshot snapshot);

    record StoredResult(String fingerprint, EnforcementSnapshot snapshot) {}
    record StoredWork(String ownerRef, EnforcementSnapshot snapshot) {}
    final class IdempotencyConflict extends RuntimeException {}
}
