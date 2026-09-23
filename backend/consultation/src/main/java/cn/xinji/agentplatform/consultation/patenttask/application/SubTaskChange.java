package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;
import cn.xinji.agentplatform.consultation.patenttask.domain.TaskReason;

import java.util.List;
import java.util.Objects;

public record SubTaskChange(
        String subTaskId,
        long expectedSubTaskVersion,
        Operation operation,
        boolean primary,
        TaskReason.Code reasonCode,
        ResponsibilitySource responsibilitySource,
        String responsibilityReference,
        List<String> completionConditionReferences,
        RecoveryPoint recoveryPoint) {
    public SubTaskChange {
        if (subTaskId == null || subTaskId.isBlank()) throw new IllegalArgumentException("subTaskId is required");
        if (expectedSubTaskVersion < 0) throw new IllegalArgumentException("expectedSubTaskVersion cannot be negative");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(responsibilitySource, "responsibilitySource");
        if (responsibilityReference == null || responsibilityReference.isBlank()) {
            throw new IllegalArgumentException("responsibilityReference is required");
        }
        completionConditionReferences = List.copyOf(Objects.requireNonNull(completionConditionReferences));
        if (operation == Operation.ADD && expectedSubTaskVersion != 0) {
            throw new IllegalArgumentException("new subtask expected version must be zero");
        }
        if (operation != Operation.ADD && expectedSubTaskVersion < 1) {
            throw new IllegalArgumentException("existing subtask expected version must be positive");
        }
        if (operation != Operation.ADD && primary) {
            throw new IllegalArgumentException("primary is declared only when a subtask is added");
        }
        if (operation == Operation.FAIL && recoveryPoint == null) {
            throw new IllegalArgumentException("failure requires a recovery point");
        }
        if (operation != Operation.FAIL && recoveryPoint != null) {
            throw new IllegalArgumentException("only failure may carry a recovery point");
        }
        if (operation == Operation.COMPLETE && completionConditionReferences.isEmpty()) {
            throw new IllegalArgumentException("completion requires deterministic condition references");
        }
        if (operation != Operation.COMPLETE && !completionConditionReferences.isEmpty()) {
            throw new IllegalArgumentException("completion references apply only to completion");
        }
    }

    public enum Operation {
        ADD, START, WAIT_FOR_USER_INPUT, WAIT_FOR_CONFIRMATION, COMPLETE,
        MARK_INSUFFICIENT_EVIDENCE, REQUIRE_HUMAN_REVIEW, FAIL
    }

    public enum ResponsibilitySource { RULE, USER, AUTHORITY_SYSTEM }
}
