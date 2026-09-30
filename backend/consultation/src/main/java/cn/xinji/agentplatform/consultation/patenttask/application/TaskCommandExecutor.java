package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentSubTask;
import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;
import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;
import cn.xinji.agentplatform.consultation.patenttask.domain.TaskEvent;

import java.time.Instant;
import java.util.*;

import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.*;

final class TaskCommandExecutor {
    private final PatentTaskCommandRepository repository;
    private final TaskAuthorizationCheck authorization;
    private final TaskCommandValidator validator = new TaskCommandValidator();

    TaskCommandExecutor(PatentTaskCommandRepository repository, TaskAuthorizationCheck authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    TaskCommandResult execute(TaskCommand command, String fingerprint, ChangeOperation operation) {
        TaskCommandReceipt received = new TaskCommandReceipt(UUID.randomUUID().toString(), command.taskId(),
                command.commandIdentity(), command.idempotencyKey(), fingerprint, Instant.now(),
                command.expectedTaskVersion(), null, command.referencedObjectVersions(), command.dependencyVersions(),
                "NOT_EVALUATED", TaskCommandReceipt.Outcome.RECEIVED, "");
        repository.recordArrival(received);
        String[] authorizationResult = {"NOT_EVALUATED"};
        try {
            TaskCommandResult result = repository.atomically(tx -> executeAtomically(
                    tx, command, fingerprint, operation, authorizationResult));
            TaskCommandReceipt.Outcome outcome = result.outcome() == TaskCommandResult.Outcome.APPLIED
                    ? TaskCommandReceipt.Outcome.APPLIED : TaskCommandReceipt.Outcome.REPLAYED;
            repository.completeArrival(received.complete(result.snapshot().version(), authorizationResult[0], outcome, ""));
            return result;
        } catch (TaskCommandException error) {
            Long actualVersion = error.currentVersion() != null ? error.currentVersion()
                    : repository.atomically(tx -> tx.findTask(command.taskId())
                    .map(PatentTaskSnapshot::version).orElse(null));
            repository.completeArrival(received.complete(actualVersion, authorizationResult[0],
                    receiptOutcome(error.code()), error.getMessage()));
            throw error;
        }
    }

    private TaskCommandResult executeAtomically(PatentTaskCommandRepository.Transaction tx, TaskCommand command,
                                                String fingerprint, ChangeOperation operation,
                                                String[] authorizationResult) {
        PatentTaskCommandRepository.StoredCommand replay = validator.findReplayOrConflict(tx, command, fingerprint);
        PatentTaskSnapshot current = tx.findTask(command.taskId()).orElse(null);
        if (replay == null) validator.validateVersions(command, current);
        TaskAuthorizationDecision decision = authorization.revalidate(new ProtectedTaskAction(
                command.type().name(), command.taskId(), command.untrustedClaimedIdentity(), null));
        authorizationResult[0] = decision != null && decision.allowed()
                ? decision.contextReference() : denial(decision);
        requireAllowed(decision);
        if (replay != null) return new TaskCommandResult(TaskCommandResult.Outcome.REPLAYED, replay.result());
        if (current != null && current.cancelled() && command.type() != TaskCommandType.RESUME
                && command.type() != TaskCommandType.CANCEL) {
            throw failure(CANCELLED_TASK, "cancelled task rejects late business commands", current.version());
        }
        PatentTaskSnapshot next = withEvent(operation.apply(current), current, command, decision.contextReference());
        if (current == null) tx.create(next); else tx.replace(command.expectedTaskVersion(), next);
        tx.record(new PatentTaskCommandRepository.StoredCommand(command.taskId(), command.commandIdentity(),
                command.idempotencyKey(), fingerprint, next));
        return new TaskCommandResult(TaskCommandResult.Outcome.APPLIED, next);
    }

    private PatentTaskSnapshot withEvent(PatentTaskSnapshot next, PatentTaskSnapshot current,
                                         TaskCommand command, String authorizationReference) {
        String taskId = current == null ? command.taskId() : current.taskId();
        List<TaskEvent> events = new ArrayList<>(next.events());
        events.add(new TaskEvent(UUID.randomUUID().toString(), command.commandIdentity(), command.type().name(),
                current == null ? 0 : current.version(), next.version(), authorizationReference,
                command.referencedObjectVersions(), command.dependencyVersions(), relatedArtifacts(current, next, command),
                preservedArtifacts(current, next, command), resumedActions(current, next, command),
                "APPLIED", "", command.occurredAt()));
        return new PatentTaskSnapshot(taskId, next.version(), next.status(), next.aggregationRule(),
                next.cancelled(), next.cancellationRecord(), next.subTasks(), next.reasons(), next.objectVersions(),
                next.dependencyVersions(), events, next.resultFocus(), next.correctionState(), next.evidenceState(),
                next.retrievalState(), next.comparisonState(), next.humanState());
    }

    private List<String> relatedArtifacts(PatentTaskSnapshot current, PatentTaskSnapshot next, TaskCommand command) {
        if (command.type() == TaskCommandType.CORRECT && next.correctionState().impacts().size()
                > (current == null ? 0 : current.correctionState().impacts().size())) {
            return next.correctionState().impacts().get(next.correctionState().impacts().size() - 1).invalidated();
        }
        return resumedPoint(current, next, command)
                .flatMap(point -> java.util.stream.Stream.of(
                        point.committedArtifactReferences().stream(), point.incompleteActionReferences().stream(),
                        java.util.stream.Stream.of(point.failedExecutionReference(), point.retryCommandReference()))
                        .flatMap(stream -> stream)).distinct().toList();
    }

    private List<String> preservedArtifacts(PatentTaskSnapshot current, PatentTaskSnapshot next, TaskCommand command) {
        if (command.type() == TaskCommandType.CORRECT && next.correctionState().impacts().size()
                > (current == null ? 0 : current.correctionState().impacts().size())) {
            return next.correctionState().impacts().get(next.correctionState().impacts().size() - 1).retained();
        }
        return resumedPoint(current, next, command)
                .flatMap(point -> point.committedArtifactReferences().stream()).distinct().toList();
    }

    private List<String> resumedActions(PatentTaskSnapshot current, PatentTaskSnapshot next, TaskCommand command) {
        if (command.type() == TaskCommandType.CORRECT && next.correctionState().impacts().size()
                > (current == null ? 0 : current.correctionState().impacts().size())) {
            return next.correctionState().impacts().get(next.correctionState().impacts().size() - 1).recomputeActions();
        }
        return resumedPoint(current, next, command)
                .flatMap(point -> point.incompleteActionReferences().stream()).distinct().toList();
    }

    private java.util.stream.Stream<RecoveryPoint> resumedPoint(PatentTaskSnapshot current,
                                                                  PatentTaskSnapshot next, TaskCommand command) {
        if (command.type() != TaskCommandType.RESUME || current == null || current.cancelled()) {
            return java.util.stream.Stream.empty();
        }
        return current.subTasks().stream().filter(before -> before.recoveryPoint() != null)
                .filter(before -> next.subTasks().stream().anyMatch(after ->
                        after.subTaskId().equals(before.subTaskId()) && after.version() == before.version() + 1
                                && after.recoveryPoint() == null))
                .map(PatentSubTask::recoveryPoint);
    }

    private void requireAllowed(TaskAuthorizationDecision decision) {
        if (decision == null || !decision.allowed()) throw failure(ACCESS_DENIED, denial(decision), null);
    }

    private String denial(TaskAuthorizationDecision decision) {
        return decision == null ? "authorization unavailable" : decision.denialReason();
    }

    private TaskCommandReceipt.Outcome receiptOutcome(TaskCommandException.Code code) {
        return switch (code) {
            case PAYLOAD_CONFLICT -> TaskCommandReceipt.Outcome.REJECTED_PAYLOAD_CONFLICT;
            case VERSION_CONFLICT -> TaskCommandReceipt.Outcome.REJECTED_STALE_VERSION;
            case OBJECT_VERSION_CONFLICT -> TaskCommandReceipt.Outcome.REJECTED_OBJECT_VERSION;
            case DEPENDENCY_VERSION_CONFLICT -> TaskCommandReceipt.Outcome.REJECTED_DEPENDENCY_VERSION;
            case ACCESS_DENIED -> TaskCommandReceipt.Outcome.REJECTED_AUTHORIZATION;
            case CANCELLED_TASK -> TaskCommandReceipt.Outcome.REJECTED_CANCELLED;
            case TASK_NOT_FOUND -> TaskCommandReceipt.Outcome.REJECTED_NOT_FOUND;
            case TASK_ALREADY_EXISTS -> TaskCommandReceipt.Outcome.REJECTED_ALREADY_EXISTS;
            case INVALID_COMMAND -> TaskCommandReceipt.Outcome.REJECTED_INVALID;
        };
    }

    private TaskCommandException failure(TaskCommandException.Code code, String message, Long version) {
        return new TaskCommandException(code, message, version);
    }

    @FunctionalInterface
    interface ChangeOperation { PatentTaskSnapshot apply(PatentTaskSnapshot current); }
}
