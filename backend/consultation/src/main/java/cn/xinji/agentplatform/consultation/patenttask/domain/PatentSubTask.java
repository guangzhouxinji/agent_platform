package cn.xinji.agentplatform.consultation.patenttask.domain;

import java.util.List;
import java.util.Objects;

public record PatentSubTask(
        String subTaskId,
        long version,
        boolean primary,
        PatentTaskStatus status,
        boolean hasLegalContinuation,
        List<TaskReason> reasons,
        RecoveryPoint recoveryPoint,
        String lastResponsibilityReference) {
    public PatentSubTask {
        if (subTaskId == null || subTaskId.isBlank()) throw new IllegalArgumentException("subTaskId is required");
        if (version < 1) throw new IllegalArgumentException("subtask version must be positive");
        Objects.requireNonNull(status, "status");
        if (lastResponsibilityReference == null || lastResponsibilityReference.isBlank()) {
            throw new IllegalArgumentException("lastResponsibilityReference is required");
        }
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (status == PatentTaskStatus.TECHNICAL_FAILURE && recoveryPoint == null) {
            throw new IllegalArgumentException("technical failure requires an exact recovery point");
        }
        if (status != PatentTaskStatus.TECHNICAL_FAILURE && recoveryPoint != null) {
            throw new IllegalArgumentException("only technical failure may carry a recovery point");
        }
    }

    public PatentSubTask resume(String responsibilityReference) {
        if (status != PatentTaskStatus.TECHNICAL_FAILURE || recoveryPoint == null) {
            throw new IllegalStateException("subtask is not recoverable");
        }
        return new PatentSubTask(subTaskId, version + 1, primary, PatentTaskStatus.IN_PROGRESS,
                true, List.of(), null, responsibilityReference);
    }
}
