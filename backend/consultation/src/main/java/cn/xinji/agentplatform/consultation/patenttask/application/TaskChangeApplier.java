package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.*;

import java.util.*;

import static cn.xinji.agentplatform.consultation.patenttask.application.SubTaskChange.Operation.*;
import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.INVALID_COMMAND;
import static cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskStatus.*;

final class TaskChangeApplier {
    private final TaskCompletionCheck completionCheck;
    private final PatentHumanState.ReceiptAuthority receiptAuthority;

    TaskChangeApplier(TaskCompletionCheck completionCheck, PatentHumanState.ReceiptAuthority receiptAuthority) {
        this.completionCheck = Objects.requireNonNull(completionCheck);
        this.receiptAuthority = Objects.requireNonNull(receiptAuthority);
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
        ResultFocusState focus = current == null ? ResultFocusState.empty() : current.resultFocus();
        if (change.resultFocusAction() != null) {
            try {
                focus = focus.apply(change.resultFocusAction(), current == null ? 1 : current.version() + 1);
            } catch (IllegalArgumentException error) {
                fail(error.getMessage(), current);
            }
        }
        PatentCorrectionState corrections = current == null ? PatentCorrectionState.empty() : current.correctionState();
        if (change.correctionAction() != null) {
            try {
                corrections = corrections.apply(change.correctionAction());
                if (change.correctionAction().kind() == PatentCorrectionAction.Kind.CORRECT) {
                    for (String key : corrections.impacts().get(corrections.impacts().size() - 1).invalidated()) {
                        PatentCorrectionAction.Artifact artifact = corrections.artifacts().get(key);
                        if (artifact.type() == PatentCorrectionAction.Type.RESULT_SET) {
                            String[] identity = artifact.id().split(":", 2);
                            if (identity.length != 2) throw new IllegalArgumentException("result set artifact must use result:<id>");
                            if (focus.resultSets().containsKey(identity[1] + "@" + artifact.version())) {
                                focus = focus.apply(ResultFocusAction.availability(artifact.branchId(),
                                        identity[1], artifact.version(), false), current.version() + 1);
                            }
                        }
                    }
                }
            } catch (IllegalArgumentException error) {
                fail(error.getMessage(), current);
            }
        }
        PatentEvidenceState evidence = current == null ? PatentEvidenceState.empty() : current.evidenceState();
        if (change.evidenceAction() != null) {
            try {
                evidence = evidence.apply(change.evidenceAction(), corrections);
            } catch (IllegalArgumentException error) {
                fail(error.getMessage(), current);
            }
        }
        PatentRetrievalState retrieval = current == null ? PatentRetrievalState.empty() : current.retrievalState();
        if (change.retrievalAction() != null) {
            try {
                retrieval = retrieval.apply(change.retrievalAction(), change.resultFocusAction());
            } catch (IllegalArgumentException error) {
                fail(error.getMessage(), current);
            }
        }
        PatentComparisonState comparisons = current == null ? PatentComparisonState.empty() : current.comparisonState();
        if (change.comparisonAction() != null) {
            try {
                comparisons = comparisons.apply(change.comparisonAction(), focus, corrections, evidence);
            } catch (IllegalArgumentException | IllegalStateException error) {
                fail(error.getMessage(), current);
            }
        }
        PatentHumanState human = current == null ? PatentHumanState.empty() : current.humanState();
        if (change.humanAction() != null) {
            try {
                human = human.apply(change.humanAction(), current, receiptAuthority);
            } catch (IllegalArgumentException error) {
                fail(error.getMessage(), current);
            }
        }
        return new AppliedChange(List.copyOf(existing.values()), objects, dependencies, focus, corrections,
                evidence, retrieval, comparisons, human);
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
                         Map<String, Long> dependencyVersions, ResultFocusState resultFocus,
                         PatentCorrectionState correctionState, PatentEvidenceState evidenceState,
                         PatentRetrievalState retrievalState, PatentComparisonState comparisonState,
                         PatentHumanState humanState) {}
}
