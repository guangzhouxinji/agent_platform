package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.*;

import java.util.*;

import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.*;

public final class PatentTaskCommandService {
    private final PatentTaskCommandRepository repository;
    private final TaskAuthorizationCheck authorization;
    private final RecoveryBoundaryCheck recoveryBoundary;
    private final TaskChangeApplier changeApplier;
    private final TaskCommandExecutor executor;
    private final TaskRecoveryValidator recoveryValidator = new TaskRecoveryValidator();

    /** Safe default: ordinary changes work, but completion and recovery require explicit approved checks. */
    public PatentTaskCommandService(PatentTaskCommandRepository repository, TaskAuthorizationCheck authorization) {
        this(repository, authorization, (current, subTask, change) -> false, denyRecovery());
    }

    public PatentTaskCommandService(PatentTaskCommandRepository repository,
                                    TaskAuthorizationCheck authorization,
                                    TaskCompletionCheck completionCheck,
                                    RecoveryBoundaryCheck recoveryBoundary) {
        this(repository, authorization, completionCheck, recoveryBoundary, receipt -> false);
    }

    public PatentTaskCommandService(PatentTaskCommandRepository repository,
                                    TaskAuthorizationCheck authorization,
                                    TaskCompletionCheck completionCheck,
                                    RecoveryBoundaryCheck recoveryBoundary,
                                    PatentHumanState.ReceiptAuthority receiptAuthority) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.changeApplier = new TaskChangeApplier(Objects.requireNonNull(completionCheck),
                Objects.requireNonNull(receiptAuthority));
        this.recoveryBoundary = Objects.requireNonNull(recoveryBoundary);
        this.executor = new TaskCommandExecutor(repository, authorization);
    }

    public TaskCommandResult create(TaskCommand command, TaskChange initial) {
        requireType(command, TaskCommandType.CREATE);
        return executor.execute(command, TaskCommandFingerprint.forChange(command, initial), current -> {
            if (current != null) throw failure(TASK_ALREADY_EXISTS, "task already exists", current.version());
            return changedSnapshot(command, null, initial);
        });
    }

    public TaskCommandResult continueTask(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.CONTINUE, change);
    }

    public TaskCommandResult supplement(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.SUPPLEMENT, change);
    }

    public TaskCommandResult confirm(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.CONFIRM, change);
    }

    public TaskCommandResult correct(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.CORRECT, change);
    }

    public TaskCommandResult select(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.SELECT, change);
    }

    public TaskCommandResult prepareHandoff(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.HUMAN_HANDOFF, change);
    }

    public TaskCommandResult receiveHumanResult(TaskCommand command, TaskChange change) {
        return change(command, TaskCommandType.HUMAN_RETURN, change);
    }

    public TaskCommandResult cancel(TaskCommand command) {
        requireType(command, TaskCommandType.CANCEL);
        return executor.execute(command, TaskCommandFingerprint.forSimple(command, "cancel"), currentValue -> {
            PatentTaskSnapshot current = requireCurrent(currentValue);
            if (current.cancelled()) {
                throw failure(CANCELLED_TASK, "task is already cancelled", current.version());
            }
            CancellationRecord cancellation = new CancellationRecord(current.version(),
                    current.subTasks().stream().map(task -> "subtask:" + task.subTaskId()
                            + "/version:" + task.version()).toList(),
                    "stop-request:" + command.commandIdentity(), null);
            return rebuild(current, current.version() + 1, true, cancellation, current.subTasks(),
                    current.objectVersions(), current.dependencyVersions());
        });
    }

    public TaskCommandResult resume(TaskCommand command, String subTaskId) {
        requireType(command, TaskCommandType.RESUME);
        return executor.execute(command, TaskCommandFingerprint.forSimple(command, "failure:" + subTaskId), currentValue -> {
            PatentTaskSnapshot current = requireCurrent(currentValue);
            PatentSubTask target = current.subTasks().stream()
                    .filter(task -> task.subTaskId().equals(subTaskId)).findFirst()
                    .orElseThrow(() -> failure(INVALID_COMMAND, "unknown recovery subtask", current.version()));
            RecoveryPoint point = target.recoveryPoint();
            if (target.status() != PatentTaskStatus.TECHNICAL_FAILURE || point == null) {
                throw failure(INVALID_COMMAND, "subtask is not recoverable", current.version());
            }
            recoveryValidator.validate(current, target, point);
            RecoveryBoundaryCheck.Validation boundary = recoveryBoundary.validateFailureRecovery(point, current);
            if (!boundary.allowed()) throw failure(INVALID_COMMAND, boundary.reason(), current.version());
            List<PatentSubTask> resumed = current.subTasks().stream()
                    .map(task -> task.subTaskId().equals(subTaskId)
                            ? task.resume("recovery:" + command.commandIdentity()) : task).toList();
            return rebuild(current, current.version() + 1, false, null, resumed,
                    current.objectVersions(), current.dependencyVersions());
        });
    }

    public TaskCommandResult resume(TaskCommand command) {
        requireType(command, TaskCommandType.RESUME);
        return executor.execute(command, TaskCommandFingerprint.forSimple(command, "cancelled-task"), currentValue -> {
            PatentTaskSnapshot current = requireCurrent(currentValue);
            if (!current.cancelled()) throw failure(INVALID_COMMAND, "task is not cancelled", current.version());
            RecoveryBoundaryCheck.Validation boundary = recoveryBoundary.validateCancelledRecovery(current);
            if (!boundary.allowed()) throw failure(INVALID_COMMAND, boundary.reason(), current.version());
            return rebuild(current, current.version() + 1, false, null, current.subTasks(),
                    current.objectVersions(), current.dependencyVersions());
        });
    }

    public PatentTaskSnapshot snapshot(String taskId) {
        requireAllowed(authorization.revalidate(new ProtectedTaskAction("READ_SNAPSHOT", taskId, null, null)));
        return repository.atomically(tx -> tx.findTask(taskId)
                .orElseThrow(() -> failure(TASK_NOT_FOUND, "task not found", null)));
    }

    /** The caller claim is retained only as an attack-surface check and never selects the required scope. */
    public PatentTaskSnapshot snapshot(String taskId, String untrustedScopeClaim) {
        requireAllowed(authorization.revalidate(new ProtectedTaskAction("READ_SNAPSHOT", taskId,
                null, untrustedScopeClaim)));
        return repository.atomically(tx -> tx.findTask(taskId)
                .orElseThrow(() -> failure(TASK_NOT_FOUND, "task not found", null)));
    }

    public PatentTaskSnapshot snapshot(String taskId, long version) {
        requireAllowed(authorization.revalidate(new ProtectedTaskAction(
                "READ_HISTORICAL_SNAPSHOT", taskId, null, null)));
        return repository.atomically(tx -> tx.findSnapshot(taskId, version)
                .orElseThrow(() -> failure(TASK_NOT_FOUND, "task snapshot not found", null)));
    }

    public PatentTaskSnapshot snapshot(String taskId, long version, String untrustedScopeClaim) {
        requireAllowed(authorization.revalidate(new ProtectedTaskAction("READ_HISTORICAL_SNAPSHOT", taskId,
                null, untrustedScopeClaim)));
        return repository.atomically(tx -> tx.findSnapshot(taskId, version)
                .orElseThrow(() -> failure(TASK_NOT_FOUND, "task snapshot not found", null)));
    }

    private TaskCommandResult change(TaskCommand command, TaskCommandType expectedType, TaskChange change) {
        requireType(command, expectedType);
        if (change.correctionAction() != null && (expectedType != TaskCommandType.CORRECT
                && expectedType != TaskCommandType.CONTINUE
                || change.correctionAction().kind() == PatentCorrectionAction.Kind.CORRECT
                && expectedType != TaskCommandType.CORRECT)) {
            throw failure(INVALID_COMMAND, "correction action does not match command type", null);
        }
        if (change.evidenceAction() != null && expectedType != TaskCommandType.CONTINUE) {
            throw failure(INVALID_COMMAND, "evidence action does not match command type", null);
        }
        if (change.retrievalAction() != null && (change.retrievalAction().outcome()
                == PatentRetrievalAction.Outcome.SCOPE_CONFIRMED
                ? expectedType != TaskCommandType.CONFIRM : expectedType != TaskCommandType.CONTINUE)) {
            throw failure(INVALID_COMMAND, "retrieval action does not match command type", null);
        }
        if (change.comparisonAction() != null && expectedType != TaskCommandType.CONTINUE) {
            throw failure(INVALID_COMMAND, "comparison action does not match command type", null);
        }
        if (change.humanAction() != null && (change.humanAction().kind() == PatentHumanAction.Kind.PREPARE
                ? expectedType != TaskCommandType.HUMAN_HANDOFF : expectedType != TaskCommandType.HUMAN_RETURN)) {
            throw failure(INVALID_COMMAND, "human action does not match command type", null);
        }
        if (change.resultFocusAction() != null) {
            ResultFocusAction.Kind kind = change.resultFocusAction().kind();
            boolean selection = kind == ResultFocusAction.Kind.SELECT
                    || kind == ResultFocusAction.Kind.BIND_EXPLICIT
                    || kind == ResultFocusAction.Kind.BIND_CURRENT
                    || kind == ResultFocusAction.Kind.BIND_LATEST_DELIVERED;
            if (selection != (expectedType == TaskCommandType.SELECT)
                    || (!selection && expectedType != TaskCommandType.CONTINUE)) {
                throw failure(INVALID_COMMAND, "focus action does not match command type", null);
            }
        }
        return executor.execute(command, TaskCommandFingerprint.forChange(command, change),
                current -> changedSnapshot(command, requireCurrent(current), change));
    }

    private PatentTaskSnapshot changedSnapshot(TaskCommand command, PatentTaskSnapshot current, TaskChange change) {
        TaskChangeApplier.AppliedChange applied = changeApplier.apply(current, change, command);
        return rebuild(current, current == null ? 1 : current.version() + 1, false, null,
                applied.subTasks(), applied.objectVersions(), applied.dependencyVersions(),
                applied.resultFocus(), applied.correctionState(), applied.evidenceState(), applied.retrievalState(),
                applied.comparisonState(), applied.humanState());
    }

    private PatentTaskSnapshot rebuild(PatentTaskSnapshot current, long version, boolean cancelled,
                                       CancellationRecord cancellation, List<PatentSubTask> subTasks,
                                       Map<String, Long> objectVersions, Map<String, Long> dependencyVersions) {
        return rebuild(current, version, cancelled, cancellation, subTasks, objectVersions,
                dependencyVersions, current == null ? ResultFocusState.empty() : current.resultFocus(),
                current == null ? PatentCorrectionState.empty() : current.correctionState(),
                current == null ? PatentEvidenceState.empty() : current.evidenceState(),
                current == null ? PatentRetrievalState.empty() : current.retrievalState(),
                current == null ? PatentComparisonState.empty() : current.comparisonState(),
                current == null ? PatentHumanState.empty() : current.humanState());
    }

    private PatentTaskSnapshot rebuild(PatentTaskSnapshot current, long version, boolean cancelled,
                                       CancellationRecord cancellation, List<PatentSubTask> subTasks,
                                       Map<String, Long> objectVersions, Map<String, Long> dependencyVersions,
                                       ResultFocusState resultFocus, PatentCorrectionState correctionState,
                                       PatentEvidenceState evidenceState, PatentRetrievalState retrievalState,
                                       PatentComparisonState comparisonState, PatentHumanState humanState) {
        TaskAggregation aggregation = PatentTaskStateAggregator.aggregate(subTasks, cancelled);
        return new PatentTaskSnapshot(current == null ? "pending" : current.taskId(), version,
                aggregation.status(), aggregation.matchedRule(), cancelled, cancellation, subTasks,
                aggregation.reasons(), objectVersions, dependencyVersions,
                current == null ? List.of() : current.events(), resultFocus, correctionState, evidenceState,
                retrievalState, comparisonState, humanState);
    }


    private PatentTaskSnapshot requireCurrent(PatentTaskSnapshot current) {
        if (current == null) throw failure(TASK_NOT_FOUND, "task not found", null);
        return current;
    }

    private void requireAllowed(TaskAuthorizationDecision decision) {
        if (decision == null || !decision.allowed()) {
            throw failure(ACCESS_DENIED, denial(decision), null);
        }
    }

    private String denial(TaskAuthorizationDecision decision) {
        return decision == null ? "authorization unavailable" : decision.denialReason();
    }

    private void requireType(TaskCommand command, TaskCommandType type) {
        if (command.type() != type) throw failure(INVALID_COMMAND, "expected command type " + type, null);
    }


    private TaskCommandException failure(TaskCommandException.Code code, String message, Long version) {
        return new TaskCommandException(code, message, version);
    }

    private static RecoveryBoundaryCheck denyRecovery() {
        return new RecoveryBoundaryCheck() {
            @Override public Validation validateFailureRecovery(RecoveryPoint point, PatentTaskSnapshot current) {
                return Validation.deny("recovery boundary has not been established");
            }
            @Override public Validation validateCancelledRecovery(PatentTaskSnapshot current) {
                return Validation.deny("cancelled-task recovery boundary has not been established");
            }
        };
    }

}
