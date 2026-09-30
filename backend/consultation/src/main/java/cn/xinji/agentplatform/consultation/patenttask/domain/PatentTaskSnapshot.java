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
        List<TaskEvent> events,
        ResultFocusState resultFocus,
        PatentCorrectionState correctionState,
        PatentEvidenceState evidenceState,
        PatentRetrievalState retrievalState,
        PatentComparisonState comparisonState,
        PatentHumanState humanState) {
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
        Objects.requireNonNull(resultFocus, "resultFocus");
        Objects.requireNonNull(correctionState, "correctionState");
        Objects.requireNonNull(evidenceState, "evidenceState");
        Objects.requireNonNull(retrievalState, "retrievalState");
        Objects.requireNonNull(comparisonState, "comparisonState");
        Objects.requireNonNull(humanState, "humanState");
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, ResultFocusState.empty(), PatentCorrectionState.empty(),
                PatentEvidenceState.empty(), PatentRetrievalState.empty(), PatentComparisonState.empty(),
                PatentHumanState.empty());
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events, ResultFocusState resultFocus) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, resultFocus, PatentCorrectionState.empty(),
                PatentEvidenceState.empty(), PatentRetrievalState.empty(), PatentComparisonState.empty(),
                PatentHumanState.empty());
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events, ResultFocusState resultFocus,
                              PatentCorrectionState correctionState) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, resultFocus, correctionState,
                PatentEvidenceState.empty(), PatentRetrievalState.empty(), PatentComparisonState.empty(),
                PatentHumanState.empty());
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events, ResultFocusState resultFocus,
                              PatentCorrectionState correctionState, PatentEvidenceState evidenceState) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, resultFocus, correctionState, evidenceState,
                PatentRetrievalState.empty(), PatentComparisonState.empty(), PatentHumanState.empty());
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events, ResultFocusState resultFocus,
                              PatentCorrectionState correctionState, PatentEvidenceState evidenceState,
                              PatentRetrievalState retrievalState) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, resultFocus, correctionState, evidenceState,
                retrievalState, PatentComparisonState.empty(), PatentHumanState.empty());
    }

    public PatentTaskSnapshot(String taskId, long version, PatentTaskStatus status, int aggregationRule,
                              boolean cancelled, CancellationRecord cancellationRecord,
                              List<PatentSubTask> subTasks, List<TaskReason> reasons,
                              Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                              List<TaskEvent> events, ResultFocusState resultFocus,
                              PatentCorrectionState correctionState, PatentEvidenceState evidenceState,
                              PatentRetrievalState retrievalState, PatentComparisonState comparisonState) {
        this(taskId, version, status, aggregationRule, cancelled, cancellationRecord, subTasks, reasons,
                objectVersions, dependencyVersions, events, resultFocus, correctionState, evidenceState,
                retrievalState, comparisonState, PatentHumanState.empty());
    }

    public RecoveryPoint recoveryPoint(String subTaskId) {
        return subTasks.stream().filter(task -> task.subTaskId().equals(subTaskId))
                .map(PatentSubTask::recoveryPoint).filter(Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no recovery point for subtask " + subTaskId));
    }
}
