package cn.xinji.agentplatform.consultation.patenttask.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Constrained business intentions; callers never submit a replacement task snapshot. */
public record TaskChange(
        List<SubTaskChange> subTaskChanges,
        Map<String, Long> objectVersionUpdates,
        Map<String, Long> dependencyVersionUpdates) {
    public TaskChange {
        subTaskChanges = List.copyOf(Objects.requireNonNull(subTaskChanges));
        objectVersionUpdates = Map.copyOf(Objects.requireNonNull(objectVersionUpdates));
        dependencyVersionUpdates = Map.copyOf(Objects.requireNonNull(dependencyVersionUpdates));
        long distinct = subTaskChanges.stream().map(SubTaskChange::subTaskId).distinct().count();
        if (distinct != subTaskChanges.size()) {
            throw new IllegalArgumentException("a command may change each subtask at most once");
        }
    }
}
