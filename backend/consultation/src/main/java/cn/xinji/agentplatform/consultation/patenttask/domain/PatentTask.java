package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable-version task aggregate used by persistence ports; it is not a database model. */
public final class PatentTask {
    private final String taskId;
    private final NavigableMap<Long, PatentTaskSnapshot> snapshots = new TreeMap<>();

    public PatentTask(PatentTaskSnapshot initial) {
        taskId = initial.taskId();
        snapshots.put(initial.version(), initial);
    }

    public PatentTaskSnapshot current() {
        return snapshots.lastEntry().getValue();
    }

    public Optional<PatentTaskSnapshot> snapshot(long version) {
        return Optional.ofNullable(snapshots.get(version));
    }

    public void append(long expectedVersion, PatentTaskSnapshot next) {
        if (!taskId.equals(next.taskId())) throw new IllegalArgumentException("task identity cannot change");
        if (current().version() != expectedVersion || next.version() != expectedVersion + 1) {
            throw new IllegalStateException("task version conflict");
        }
        if (snapshots.containsKey(next.version())) throw new IllegalStateException("task version cannot be overwritten");
        snapshots.put(next.version(), next);
    }
}
