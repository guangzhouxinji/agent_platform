package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.*;

import java.util.*;

import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.Operation.*;
import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.INVALID_COMMAND;
import static cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskStatus.*;

final class TaskChangeApplier {
    private final TaskCompletionCheck completionCheck;

    TaskChangeApplier(TaskCompletionCheck completionCheck) {
        this.completionCheck = Objects.requireNonNull(completionCheck);
    }

    AppliedChange apply(PatentTaskSnapshot current, TaskChange change, TaskCommand command) {
        Map<String, PatentSubTask> existing = new LinkedHashMap<>();
        if (current != null) current.subTasks().forEach(task -> existing.put(task.subTaskId(), task));
        for (SubTaskChange requested : change.subTaskChanges()) {
            PatentSubTask before = existing.get(requested.subTaskId());
            if (requested.operation() == ADD) {
                if (before != null) fail("subtask identity already exists", current);
                existing.put(requested.subTaskId(), new PatentSubTask(requested.subTaskId(), 1,
                        requested.primary(), IN_PROGRESS, true, List.of(), null,
                        requested.responsibilityReference()));
            } else {
                if (before == null) fail("unknown subtask", current);
                if (before.version() != requested.expectedSubTaskVersion()) {
                    throw TaskCommandException.objectConflict(command.taskId(), current.version(),
                            requested.subTaskId(), requested.expectedSubTaskVersion(), before.version());
                }
                existing.put(before.subTaskId(), transition(current, before, requested));
            }
        }
        if (existing.isEmpty()) fail("at least one subtask is required", current);
        Map<String, Long> objects = mergeVersions(current == null ? Map.of() : current.objectVersions(),
                change.objectVersionUpdates(), "object", current);
        Map<String, Long> dependencies = mergeVersions(current == null ? Map.of() : current.dependencyVersions(),
                change.dependencyVersionUpdates(), "dependency", current);
        return new AppliedChange(List.copyOf(existing.values()), objects, dependencies);
    }

    private PatentSubTask transition(PatentTaskSnapshot current, PatentSubTask before, SubTaskChange change) {
        PatentTaskStatus target = target(change.operation());
        if (!legal(before.status(), change.operation())) {
            fail("illegal subtask transition " + before.status() + " -> " + target, current);
        }
        if (change.operation() == COMPLETE) {
            if (change.responsibilitySource() != SubTaskChange.ResponsibilitySource.RULE
                    || !completionCheck.deterministicConditionsSatisfied(current, before, change)) {
                fail("completion conditions were not established by an approved deterministic rule", current);
            }
        }
        if (change.operation() == FAIL) validateRecoveryPointAtFailure(current, before, change.recoveryPoint());
        List<TaskReason> reasons = reason(change, current);
        boolean continuation = target == IN_PROGRESS;
        return new PatentSubTask(before.subTaskId(), before.version() + 1, before.primary(), target,
                continuation, reasons, change.recoveryPoint(), change.responsibilityReference());
    }

    private List<TaskReason> reason(SubTaskChange change, PatentTaskSnapshot current) {
        TaskReason.Code expected = switch (change.operation()) {
            case FAIL -> TaskReason.Code.TECHNICAL_FAILURE;
            case MARK_INSUFFICIENT_EVIDENCE -> change.reasonCode();
            default -> null;
        };
        if (change.operation() == MARK_INSUFFICIENT_EVIDENCE && expected == null) {
            fail("insufficient evidence reason is required", current);
        }
        if (change.operation() == MARK_INSUFFICIENT_EVIDENCE
                && !Set.of(TaskReason.Code.NO_RESULT, TaskReason.Code.NO_PERMISSION,
                TaskReason.Code.SOURCE_INVALID, TaskReason.Code.INSUFFICIENT_EVIDENCE).contains(expected)) {
            fail("technical failure cannot be recorded as an evidence reason", current);
        }
        if (expected == null) {
            if (change.reasonCode() != null) fail("reason is not valid for operation", current);
            return List.of();
        }
        if (change.operation() == FAIL && change.reasonCode() != null
                && change.reasonCode() != TaskReason.Code.TECHNICAL_FAILURE) {
            fail("failure reason must be technical failure", current);
        }
        return List.of(new TaskReason(expected, change.operation().name(), change.responsibilitySource(),
                change.responsibilityReference()));
    }

    private PatentTaskStatus target(SubTaskChange.Operation operation) {
        return switch (operation) {
            case START, ADD -> IN_PROGRESS;
            case WAIT_FOR_USER_INPUT -> WAITING_FOR_USER_INPUT;
            case WAIT_FOR_CONFIRMATION -> WAITING_FOR_CONFIRMATION;
            case COMPLETE -> COMPLETED;
            case MARK_INSUFFICIENT_EVIDENCE -> INSUFFICIENT_EVIDENCE;
            case REQUIRE_HUMAN_REVIEW -> REQUIRES_HUMAN_REVIEW;
            case FAIL -> TECHNICAL_FAILURE;
        };
    }

    private void validateRecoveryPointAtFailure(PatentTaskSnapshot current, PatentSubTask before,
                                                RecoveryPoint point) {
        if (current == null || point.failedFromTaskVersion() != current.version()
                || !point.subTaskId().equals(before.subTaskId())) {
            fail("recovery point must identify the exact pre-failure task and subtask", current);
        }
        point.contextVersionReferences().forEach((key, version) -> {
            if (!Objects.equals(current.objectVersions().get(key), version)) {
                fail("recovery context version does not match current task", current);
            }
        });
        point.dependencyVersions().forEach((key, version) -> {
            if (!Objects.equals(current.dependencyVersions().get(key), version)) {
                fail("recovery dependency version does not match current task", current);
            }
        });
    }

    private boolean legal(PatentTaskStatus before, SubTaskChange.Operation operation) {
        if (before == COMPLETED) return false;
        if (before == TECHNICAL_FAILURE) return false; // Failure recovery has its own guarded command.
        return switch (operation) {
            case ADD -> false;
            case START -> before != USER_CANCELLED;
            case WAIT_FOR_USER_INPUT, WAIT_FOR_CONFIRMATION, REQUIRE_HUMAN_REVIEW, FAIL ->
                    before != USER_CANCELLED;
            case COMPLETE -> before == IN_PROGRESS || before == WAITING_FOR_CONFIRMATION;
            case MARK_INSUFFICIENT_EVIDENCE -> before != USER_CANCELLED;
        };
    }

    private Map<String, Long> mergeVersions(Map<String, Long> current, Map<String, Long> updates,
                                             String kind, PatentTaskSnapshot snapshot) {
        Map<String, Long> merged = new LinkedHashMap<>(current);
        updates.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value < 1) {
                fail("invalid " + kind + " version update", snapshot);
            }
            Long old = merged.get(key);
            if (old != null && value < old) fail(kind + " version cannot move backwards", snapshot);
            merged.put(key, value);
        });
        return Map.copyOf(merged);
    }

    private void fail(String message, PatentTaskSnapshot current) {
        throw new TaskCommandException(INVALID_COMMAND, message, current == null ? null : current.version());
    }

    record AppliedChange(List<PatentSubTask> subTasks, Map<String, Long> objectVersions,
                         Map<String, Long> dependencyVersions) {}
}
