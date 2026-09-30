package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.xinji.agentplatform.consultation.patenttask.application.TaskCommandException.Code.*;

/** Executes one bounded read, then commits its actual outcome through the task command boundary. */
public final class PatentRetrievalService {
    private final PatentTaskCommandService tasks;
    private final TaskAuthorizationCheck authorization;
    private final PatentRetrievalPort source;

    public PatentRetrievalService(PatentTaskCommandService tasks, TaskAuthorizationCheck authorization,
                                  PatentRetrievalPort source) {
        this.tasks = Objects.requireNonNull(tasks);
        this.authorization = Objects.requireNonNull(authorization);
        this.source = Objects.requireNonNull(source);
    }

    public TaskCommandResult search(TaskCommand command, Request request) {
        if (command.type() != TaskCommandType.CONTINUE) throw error(INVALID_COMMAND, "expected continue", null);
        PatentTaskSnapshot current = tasks.snapshot(command.taskId());
        if (current.version() != command.expectedTaskVersion()) {
            throw error(VERSION_CONFLICT, "task changed before retrieval", current.version());
        }
        if (current.cancelled()) throw error(CANCELLED_TASK, "cancelled task cannot retrieve", current.version());
        if (request.branchId() == null || request.branchId().isBlank()
                || request.scopeReference() == null || request.scopeReference().isBlank()
                || request.methodReference() == null || request.methodReference().isBlank()
                || request.queryReference() == null || request.queryReference().isBlank()
                || request.gapReference() == null || request.gapReference().isBlank()
                || request.executionId() == null || request.executionId().isBlank()
                || request.scopeArtifactKey() == null || request.scopeArtifactKey().isBlank()) {
            throw error(INVALID_COMMAND, "retrieval requires branch, scope, method, query, gap and execution", current.version());
        }
        var scopeArtifact = current.correctionState().artifacts().get(request.scopeArtifactKey());
        if (!current.correctionState().usable(request.scopeArtifactKey()) || scopeArtifact == null
                || scopeArtifact.type() != PatentCorrectionAction.Type.SCOPE
                || !scopeArtifact.branchId().equals(request.branchId())) {
            throw error(INVALID_COMMAND, "retrieval requires an active scope version in its branch", current.version());
        }
        for (String featureKey : request.featureArtifactKeys()) {
            var feature = current.correctionState().artifacts().get(featureKey);
            if (!current.correctionState().usable(featureKey) || feature == null
                    || feature.type() != PatentCorrectionAction.Type.FEATURE
                    || !feature.branchId().equals(request.branchId())) {
                throw error(INVALID_COMMAND, "retrieval feature version is unavailable", current.version());
            }
        }
        List<PatentRetrievalAction> previous = current.retrievalState().attempts().stream()
                .filter(item -> item.branchId().equals(request.branchId())
                        && item.outcome() != PatentRetrievalAction.Outcome.SCOPE_CONFIRMED).toList();
        if (!previous.isEmpty() && !previous.get(previous.size() - 1).actualScopeReference()
                .equals(request.scopeReference())
                && current.retrievalState().attempts().stream().noneMatch(item ->
                    item.outcome() == PatentRetrievalAction.Outcome.SCOPE_CONFIRMED
                    && item.executionId().equals(request.scopeConfirmationReference())
                    && item.branchId().equals(request.branchId())
                    && item.queryReference().equals(previous.get(previous.size() - 1).actualScopeReference())
                    && item.actualScopeReference().equals(request.scopeReference()))) {
            throw error(INVALID_COMMAND, "changed scope needs recorded confirmation", current.version());
        }
        if (previous.stream().anyMatch(item -> item.executionId().equals(request.executionId())
                || item.actualScopeReference().equals(request.scopeReference())
                && item.methodReference().equals(request.methodReference())
                && item.queryReference().equals(request.queryReference()))) {
            throw error(INVALID_COMMAND, "duplicate retrieval has no new information hypothesis", current.version());
        }
        TaskAuthorizationDecision decision = authorization.revalidate(new ProtectedTaskAction(
                "SEARCH", command.taskId(), command.untrustedClaimedIdentity(), request.scopeReference()));
        if (decision == null || !decision.allowed()) {
            throw error(ACCESS_DENIED, decision == null ? "authorization unavailable" : decision.denialReason(),
                    current.version());
        }
        PatentRetrievalPort.Response response;
        try {
            response = source.search(request.scopeReference(), request.methodReference(), request.queryReference());
        } catch (RuntimeException failure) {
            response = new PatentRetrievalPort.Response(PatentRetrievalAction.Outcome.TECHNICAL_FAILURE,
                    request.scopeReference(), request.methodReference(), List.of(), List.of(),
                    List.of("fixture-source-failure"), failure.getClass().getSimpleName());
        }
        if (!request.scopeReference().equals(response.actualScopeReference())
                || !request.methodReference().equals(response.methodReference())) {
            throw error(INVALID_COMMAND, "source executed outside the authorized request", current.version());
        }
        boolean found = response.outcome() == PatentRetrievalAction.Outcome.RESULTS;
        if (found && (response.candidates().isEmpty() || response.sourceReferences().isEmpty())) {
            throw error(INVALID_COMMAND, "result requires candidates and sources", current.version());
        }
        ResultFocusAction result = found ? ResultFocusAction.produce(request.branchId(),
                request.resultSetId(), request.resultSetVersion(), request.parentVersion(), request.gapReference(),
                new PatentResultSet.Origin(response.actualScopeReference(), response.methodReference(),
                        response.sourceReferences(), List.of(request.executionId()), response.limitations()),
                response.candidates()) : null;
        PatentRetrievalAction record = new PatentRetrievalAction(request.executionId(), request.branchId(),
                response.actualScopeReference(), response.methodReference(), request.queryReference(),
                request.gapReference(), request.scopeConfirmationReference(), response.outcome(),
                found ? request.resultSetId() : null, found ? request.resultSetVersion() : 0,
                response.sourceReferences(), response.limitations(), response.detail());
        List<String> inputs = new java.util.ArrayList<>();
        inputs.add(request.scopeArtifactKey());
        inputs.addAll(request.featureArtifactKeys());
        PatentCorrectionAction dependency = found ? new PatentCorrectionAction(
                PatentCorrectionAction.Kind.REGISTER,
                new PatentCorrectionAction.Artifact("result:" + request.resultSetId(), request.resultSetVersion(),
                        PatentCorrectionAction.Type.RESULT_SET, request.branchId(), inputs), null, 0, null) : null;
        return tasks.continueTask(command, new TaskChange(List.of(), Map.of(), Map.of(), result,
                dependency, null, record));
    }

    public TaskCommandResult confirmScope(TaskCommand command, String branchId, String currentScope,
                                          String newScope, String reason) {
        if (command.type() != TaskCommandType.CONFIRM || reason == null || reason.isBlank()) {
            throw error(INVALID_COMMAND, "explicit scope confirmation is required", null);
        }
        PatentRetrievalAction confirmation = new PatentRetrievalAction(command.commandIdentity(), branchId,
                newScope, "scope-confirmation", currentScope, reason, null,
                PatentRetrievalAction.Outcome.SCOPE_CONFIRMED, null, 0, List.of(), List.of(), "");
        return tasks.confirm(command, new TaskChange(List.of(), Map.of(), Map.of(), null,
                null, null, confirmation));
    }

    public TaskCommandResult stop(TaskCommand command, String branchId, String scopeReference,
                                  String gapReference, PatentRetrievalAction.StopReason reason) {
        if (command.type() != TaskCommandType.CONTINUE || reason == null) {
            throw error(INVALID_COMMAND, "retrieval stop requires a reason", null);
        }
        PatentRetrievalAction record = new PatentRetrievalAction(command.commandIdentity(), branchId,
                scopeReference, "stop", "stop", gapReference, null,
                PatentRetrievalAction.Outcome.STOPPED, null, 0, List.of(), List.of(), reason.name());
        return tasks.continueTask(command, new TaskChange(List.of(), Map.of(), Map.of(), null,
                null, null, record));
    }

    public record Request(String executionId, String branchId, String scopeReference,
                          String methodReference, String queryReference, String gapReference,
                          String scopeConfirmationReference, String resultSetId,
                          long resultSetVersion, Long parentVersion, String scopeArtifactKey,
                          List<String> featureArtifactKeys) {
        public Request { featureArtifactKeys = List.copyOf(featureArtifactKeys); }
    }

    private static TaskCommandException error(TaskCommandException.Code code, String message, Long version) {
        return new TaskCommandException(code, message, version);
    }
}
