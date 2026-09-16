package cn.xinji.agentplatform.adjudication.adapter.out.memory;

import cn.xinji.agentplatform.adjudication.application.AdjudicationStore;
import cn.xinji.agentplatform.adjudication.domain.AdjudicationSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAdjudicationStore implements AdjudicationStore {
    private final Map<String, StoredResult> idempotency = new HashMap<>();
    private final Map<String, StoredWork> workItems = new HashMap<>();
    @Override public Optional<StoredResult> findByIdempotency(String actor, String operation, String key) { return Optional.ofNullable(idempotency.get(actor + "|" + operation + "|" + key)); }
    @Override public Optional<StoredWork> findByWorkItemId(String workId) { return Optional.ofNullable(workItems.get(workId)); }
    @Override public synchronized AdjudicationSnapshot save(String actor, String operation, String key, String fingerprint,
            long expectedPersistenceVersion, AdjudicationSnapshot snapshot) {
        String idempotencyKey = actor + "|" + operation + "|" + key; StoredResult old = idempotency.get(idempotencyKey);
        if (old != null) { if (!old.fingerprint().equals(fingerprint)) throw new IdempotencyConflict(); return old.snapshot(); }
        StoredWork previous = workItems.get(snapshot.work().workItemId());
        long actualVersion = previous == null ? 0 : previous.persistenceVersion();
        if (actualVersion != expectedPersistenceVersion) throw new VersionConflict();
        idempotency.put(idempotencyKey, new StoredResult(fingerprint, snapshot));
        workItems.put(snapshot.work().workItemId(), new StoredWork(
                previous == null ? actor : previous.ownerRef(), actualVersion + 1, snapshot));
        return snapshot;
    }
}
