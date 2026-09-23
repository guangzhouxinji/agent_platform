package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PatentTaskSnapshot(
        String taskId,
        long version,
        PatentTaskStatus status,
        int aggregationRule,
        boolean cancelled,
        CancellationRecord cancellationRecord,
        List<PatentSubTask> subTasks,
        List<TaskReason> reasons,
        Map<String, Long> objectVersions,
        Map<String, Long> dependencyVersions,
        List<TaskEvent> events) {
    public PatentTaskSnapshot {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        Objects.requireNonNull(status, "status");
        if (cancelled && cancellationRecord == null) {
            throw new IllegalArgumentException("cancelled task requires a cancellation record");
        }
        if (!cancelled && cancellationRecord != null) {
            throw new IllegalArgumentException("active task cannot carry a cancellation record");
        }
        subTasks = List.copyOf(subTasks);
        reasons = List.copyOf(reasons);
        objectVersions = Map.copyOf(objectVersions);
        dependencyVersions = Map.copyOf(dependencyVersions);
        events = List.copyOf(events);
    }

    public RecoveryPoint recoveryPoint(String subTaskId) {
        return subTasks.stream().filter(task -> task.subTaskId().equals(subTaskId))
                .map(PatentSubTask::recoveryPoint).filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no recovery point for subtask " + subTaskId));
    }
}
