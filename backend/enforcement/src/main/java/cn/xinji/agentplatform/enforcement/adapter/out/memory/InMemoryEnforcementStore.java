package cn.xinji.agentplatform.enforcement.adapter.out.memory;

import cn.xinji.agentplatform.enforcement.application.EnforcementStore;
import cn.xinji.agentplatform.enforcement.domain.EnforcementSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryEnforcementStore implements EnforcementStore {
    private final Map<String, StoredResult> idempotency = new HashMap<>();
    private final Map<String, StoredWork> workItems = new HashMap<>();

    @Override
    public Optional<StoredResult> findByIdempotency(String actorRef, String operation, String key) {
        return Optional.ofNullable(idempotency.get(actorRef + "|" + operation + "|" + key));
    }

    @Override
    public Optional<StoredWork> findByWorkItemId(String workItemId) {
        return Optional.ofNullable(workItems.get(workItemId));
    }

    @Override
    public synchronized EnforcementSnapshot save(String actorRef, String operation, String key, String fingerprint, EnforcementSnapshot snapshot) {
        String idempotencyKey = actorRef + "|" + operation + "|" + key;
        StoredResult prior = idempotency.get(idempotencyKey);
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)) throw new IdempotencyConflict();
            return prior.snapshot();
        }
        idempotency.put(idempotencyKey, new StoredResult(fingerprint, snapshot));
        StoredWork existing = workItems.get(snapshot.workItem().workItemId());
        String owner = existing == null ? actorRef : existing.ownerRef();
        workItems.put(snapshot.workItem().workItemId(), new StoredWork(owner, snapshot));
        return snapshot;
    }
}
