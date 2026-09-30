package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class PatentRetrievalShellContractTest {
    private final PatentTaskCommandService tasks = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(), action -> TaskAuthorizationDecision.allow("fixture-auth"));
    private long version;

    @Test void iterativeReadCreatesTraceableSnapshotsWithoutStealingFocus() {
        create();
        var service = shell(action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> result(scope, method, query));
        service.search(command("search-1"), request("execution-1", "scope-1", "query-1", 1, null, null));
        version++;
        select();
        service.search(command("search-2"), request("execution-2", "scope-1", "query-2", 2, 1L, null));
        version++;
        assertThat(tasks.snapshot("task").retrievalState().attempts()).hasSize(2);
        assertThat(tasks.snapshot("task").resultFocus().currentFocus("main").resultSetVersion()).isEqualTo(1);
        assertThat(tasks.snapshot("task").resultFocus().resultSets().get("batch@2").origin())
                .extracting(PatentResultSet.Origin::actualScopeReference,
                        PatentResultSet.Origin::methodReference).containsExactly("scope-1", "fixture-method");
    }

    @Test void repeatedActionAndScopeExpansionWithoutConfirmationDoNotReadSource() {
        create();
        AtomicInteger calls = new AtomicInteger();
        var service = shell(action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> { calls.incrementAndGet(); return result(scope, method, query); });
        service.search(command("first"), request("execution-1", "scope-1", "query-1", 1, null, null));
        version++;
        reject(() -> service.search(command("repeat"), request("execution-2", "scope-1", "query-1", 2, 1L, null)),
                TaskCommandException.Code.INVALID_COMMAND);
        reject(() -> service.search(command("expanded"), request("execution-2", "scope-2", "query-2", 2, 1L, null)),
                TaskCommandException.Code.INVALID_COMMAND);
        reject(() -> service.search(command("invented-confirmation"), request("execution-2", "scope-2", "query-2",
                2, 1L, "invented")), TaskCommandException.Code.INVALID_COMMAND);
        assertThat(calls.get()).isEqualTo(1);
        service.confirmScope(new TaskCommand("user-confirmation", "user-confirmation", TaskCommandType.CONFIRM,
                "task", version, Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z")),
                "main", "scope-1", "scope-2", "user requested wider scope");
        version++;
        tasks.correct(new TaskCommand("scope-correction", "scope-correction", TaskCommandType.CORRECT,
                "task", version, Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z")),
                new TaskChange(List.of(), Map.of(), Map.of(), null,
                        new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                                new PatentCorrectionAction.Artifact("scope", 2, PatentCorrectionAction.Type.SCOPE,
                                        "main", List.of()), "scope", 1, "confirmed scope change")));
        version++;
        service.search(command("confirmed"), request("execution-2", "scope-2", "query-2", 2, 1L,
                "user-confirmation"));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void denialAndSourceScopeDriftCannotBecomeResults() {
        create();
        AtomicInteger calls = new AtomicInteger();
        var denied = shell(action -> TaskAuthorizationDecision.deny("fixture-denial"),
                (scope, method, query) -> { calls.incrementAndGet(); return result(scope, method, query); });
        reject(() -> denied.search(command("denied"), request("execution-1", "scope-1", "query-1", 1, null, null)),
                TaskCommandException.Code.ACCESS_DENIED);
        assertThat(calls.get()).isZero();
        var drift = shell(action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> result("different-scope", method, query));
        reject(() -> drift.search(command("drift"), request("execution-1", "scope-1", "query-1", 1, null, null)),
                TaskCommandException.Code.INVALID_COMMAND);
        assertThat(tasks.snapshot("task").retrievalState().attempts()).isEmpty();
    }

    @Test void noResultIsRecordedWithoutFabricatingCandidatesOrTechnicalFailure() {
        create();
        var service = shell(action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> new PatentRetrievalPort.Response(PatentRetrievalAction.Outcome.NO_RESULT,
                        scope, method, List.of("source:fixture"), List.of(), List.of("limited-corpus"), "none"));
        service.search(command("empty"), request("execution-1", "scope-1", "query-1", 1, null, null));
        assertThat(tasks.snapshot("task").retrievalState().attempts().get(0).outcome())
                .isEqualTo(PatentRetrievalAction.Outcome.NO_RESULT);
        assertThat(tasks.snapshot("task").resultFocus().resultSets()).isEmpty();
        assertThat(tasks.snapshot("task").status()).isEqualTo(PatentTaskStatus.IN_PROGRESS);
    }

    @Test void sourceExceptionIsTechnicalFailureAndStopHasExplicitReason() {
        create();
        var service = shell(action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> { throw new IllegalStateException("fixture source unavailable"); });
        service.search(command("failed"), request("execution-1", "scope-1", "query-1", 1, null, null));
        version++;
        assertThat(tasks.snapshot("task").retrievalState().attempts().get(0).outcome())
                .isEqualTo(PatentRetrievalAction.Outcome.TECHNICAL_FAILURE);
        service.stop(command("stop"), "main", "scope-1", "gap:feature-coverage",
                PatentRetrievalAction.StopReason.TECHNICAL_BLOCKER);
        assertThat(tasks.snapshot("task").retrievalState().attempts().get(1).detail())
                .isEqualTo("TECHNICAL_BLOCKER");
        assertThat(tasks.snapshot("task").resultFocus().resultSets()).isEmpty();
    }

    private void create() {
        tasks.create(new TaskCommand("create", "create", TaskCommandType.CREATE, "task", 0,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z")), new TaskChange(List.of(
                new SubTaskChange("patent", 0, SubTaskChange.Operation.ADD, true, null,
                        SubTaskChange.ResponsibilitySource.RULE, "fixture:create", List.of(), null)), Map.of(), Map.of()));
        version = 1;
        tasks.continueTask(command("scope"), new TaskChange(List.of(), Map.of(), Map.of(), null,
                new PatentCorrectionAction(PatentCorrectionAction.Kind.REGISTER,
                        new PatentCorrectionAction.Artifact("scope", 1, PatentCorrectionAction.Type.SCOPE,
                                "main", List.of()), null, 0, null)));
        version++;
    }

    private void select() {
        tasks.continueTask(command("deliver"), new TaskChange(List.of(), Map.of(), Map.of(),
                ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "batch", 1)));
        version++;
        tasks.select(new TaskCommand("select", "select", TaskCommandType.SELECT, "task", version,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z")),
                new TaskChange(List.of(), Map.of(), Map.of(),
                        ResultFocusAction.onResult(ResultFocusAction.Kind.SELECT, "main", "batch", 1)));
        version++;
    }

    private PatentRetrievalService shell(TaskAuthorizationCheck authorization, PatentRetrievalPort port) {
        return new PatentRetrievalService(tasks, authorization, port);
    }

    private PatentRetrievalPort.Response result(String scope, String method, String query) {
        return new PatentRetrievalPort.Response(PatentRetrievalAction.Outcome.RESULTS, scope, method,
                List.of("source:fixture"), List.of(new PatentResultSet.Candidate("candidate-" + query,
                        "document-" + query + "-v1")), List.of("development-only"), "fixture result");
    }

    private PatentRetrievalService.Request request(String execution, String scope, String query, long resultVersion,
                                                   Long parent, String confirmation) {
        return new PatentRetrievalService.Request(execution, "main", scope, "fixture-method", query,
                "gap:feature-coverage", confirmation, "batch", resultVersion, parent,
                scope.equals("scope-2") ? "scope@2" : "scope@1", List.of());
    }

    private TaskCommand command(String id) {
        return new TaskCommand(id, id, TaskCommandType.CONTINUE, "task", version,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z"));
    }

    private void reject(Runnable action, TaskCommandException.Code expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TaskCommandException.class,
                error -> assertThat(error.code()).isEqualTo(expected));
    }
}
