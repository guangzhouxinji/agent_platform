package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentResultFocusContractTest {
    private final FixtureInMemoryPatentTaskCommandRepository repository =
            new FixtureInMemoryPatentTaskCommandRepository();
    private PatentTaskCommandService service = newService();
    private long version;

    @Test
    void newReorderedResultNeverStealsExplicitFocusOrChangesAnOldOrdinalBinding() {
        create();
        apply(produce("main", "results", 1, null, "initial",
                List.of(candidate("a", "A1"), candidate("b", "B1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "results", 1));
        apply(ResultFocusAction.bind("main", "results", 1, 2, "original-second"));
        apply(produce("main", "results", 2, 1L, "reorder",
                List.of(candidate("b", "B1"), candidate("a", "A1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "results", 2));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DISPLAY, "main", "results", 2));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.VIEW, "main", "results", 2));
        ResultFocusState afterView = service.snapshot("task").resultFocus();
        assertThat(afterView.currentFocus("main").resultSetVersion()).isEqualTo(1);
        assertThat(afterView.focusHistory().get("main")).hasSize(1);
        assertThat(afterView.usableBinding("original-second").candidateId()).isEqualTo("b");
        assertThat(afterView.resultSets().get("results@1").origin())
                .extracting(PatentResultSet.Origin::actualScopeReference,
                        PatentResultSet.Origin::methodReference)
                .containsExactly("fixture-scope", "fixture-method");

        service = newService();
        apply(ResultFocusAction.bindCurrent("main", 2, "just-second"));
        assertThat(service.snapshot("task").resultFocus().usableBinding("just-second"))
                .extracting(ResultFocusState.ReferenceBinding::resultSetVersion,
                        ResultFocusState.ReferenceBinding::candidateId)
                .containsExactly(1L, "b");
        apply(ResultFocusAction.bind("main", "results", 2, 2, "explicit-v2"));
        ResultFocusState selected = service.snapshot("task").resultFocus();
        assertThat(selected.currentFocus("main").resultSetVersion()).isEqualTo(2);
        assertThat(selected.usableBinding("explicit-v2").candidateId()).isEqualTo("a");
        assertThat(selected.usableBinding("original-second").candidateId()).isEqualTo("b");
        assertThat(service.snapshot("task", 4).resultFocus().currentFocus("main").resultSetVersion()).isEqualTo(1);
    }

    @Test
    void noFocusOrAmbiguityCannotGuessAndBranchesKeepSeparateFocus() {
        create();
        apply(produce("main", "a", 1, null, "initial", List.of(candidate("a", "A1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "a", 1));
        reject(ResultFocusAction.bindCurrent("main", 1, "unqualified"));
        apply(produce("main", "b", 1, null, "another", List.of(candidate("b", "B1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "b", 1));
        apply(ResultFocusAction.ambiguous("main", "that-batch", List.of("a@1", "b@1")));
        assertThat(service.snapshot("task").resultFocus().currentFocus("main")).isNull();
        assertThat(service.snapshot("task").resultFocus().ambiguities().get("that-batch"))
                .containsExactly("a@1", "b@1");

        apply(ResultFocusAction.bind("main", "b", 1, 1, "that-batch"));
        assertThat(service.snapshot("task").resultFocus().ambiguities()).doesNotContainKey("that-batch");
        assertThat(service.snapshot("task").resultFocus().usableBinding("that-batch").candidateId()).isEqualTo("b");

        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.SELECT, "main", "a", 1));
        apply(produce("other", "c", 1, null, "branch", List.of(candidate("c", "C1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "other", "c", 1));
        apply(ResultFocusAction.switchBranch("other"));
        reject(ResultFocusAction.bindCurrent("other", 1, "no-branch-focus"));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.SELECT, "other", "c", 1));
        assertThatThrownBy(() -> service.snapshot("task").resultFocus().usableBinding("that-batch"))
                .isInstanceOf(IllegalStateException.class);
        apply(ResultFocusAction.switchBranch("main"));
        assertThat(service.snapshot("task").resultFocus().currentFocus("main").resultSetId()).isEqualTo("a");
        assertThat(service.snapshot("task").resultFocus().currentFocus("other").resultSetId()).isEqualTo("c");
    }

    @Test
    void invalidationRetainsHistoryButBlocksConsumptionAndNeverSwitchesToAnotherResult() {
        create();
        apply(produce("main", "a", 1, null, "initial", List.of(candidate("a", "A1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "a", 1));
        apply(ResultFocusAction.bind("main", "a", 1, 1, "selected"));
        apply(produce("main", "b", 1, null, "later", List.of(candidate("b", "B1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "b", 1));
        apply(ResultFocusAction.availability("main", "a", 1, false));
        ResultFocusState unavailable = service.snapshot("task").resultFocus();
        assertThat(unavailable.currentFocus("main"))
                .extracting(ResultFocusState.FocusVersion::resultSetId, ResultFocusState.FocusVersion::available)
                .containsExactly("a", false);
        assertThat(unavailable.bindings()).hasSize(1);
        assertThatThrownBy(() -> unavailable.usableBinding("selected"))
                .isInstanceOf(IllegalStateException.class);
        reject(ResultFocusAction.bindCurrent("main", 1, "cannot-use"));
        apply(ResultFocusAction.availability("main", "a", 1, true));
        assertThat(service.snapshot("task").resultFocus().currentFocus("main").resultSetId()).isEqualTo("a");
    }

    @Test
    void changedFocusActionCannotReplayAsTheSameCommand() {
        create();
        ResultFocusAction first = produce("main", "a", 1, null, "initial",
                List.of(candidate("a", "A1")));
        TaskCommand command = command(TaskCommandType.CONTINUE, "same-key");
        service.continueTask(command, focus(first));
        version++;
        assertThat(service.continueTask(command, focus(first)).outcome())
                .isEqualTo(TaskCommandResult.Outcome.REPLAYED);
        assertThatThrownBy(() -> service.continueTask(command,
                focus(produce("main", "a", 1, null, "changed",
                        List.of(candidate("a", "A1"))))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT));
        ResultFocusAction differentSource = ResultFocusAction.produce("main", "a", 1, null, "initial",
                new PatentResultSet.Origin("fixture-scope", "fixture-method", List.of("source:other"),
                        List.of("execution:fixture"), List.of("not-real-data")), List.of(candidate("a", "A1")));
        assertThatThrownBy(() -> service.continueTask(command, focus(differentSource)))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT));
        assertThat(service.snapshot("task").version()).isEqualTo(version);
    }

    @Test
    void latestMeansTheLastDeliveredSnapshotInTheActiveBranchOnly() {
        create();
        apply(produce("main", "a", 1, null, "first", List.of(candidate("a", "A1"))));
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "a", 1));
        apply(produce("main", "b", 1, null, "background", List.of(candidate("b", "B1"))));
        apply(ResultFocusAction.bindLatestDelivered("main", 1, "latest"));
        assertThat(service.snapshot("task").resultFocus().usableBinding("latest").resultSetId()).isEqualTo("a");
        apply(ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "b", 1));
        apply(ResultFocusAction.bindLatestDelivered("main", 1, "new-latest"));
        assertThat(service.snapshot("task").resultFocus().usableBinding("new-latest").resultSetId()).isEqualTo("b");
    }

    @Test
    void stableCandidateIdentityCannotPointToAnotherDocumentVersion() {
        create();
        apply(produce("main", "results", 1, null, "initial",
                List.of(candidate("same", "A1"))));
        reject(produce("main", "results", 2, 1L, "new version",
                List.of(candidate("same", "B2"))));
        assertThat(service.snapshot("task").resultFocus().resultSets()).containsOnlyKeys("results@1");
    }

    private PatentTaskCommandService newService() {
        return new PatentTaskCommandService(repository,
                action -> TaskAuthorizationDecision.allow("fixture-auth"));
    }

    private void create() {
        service.create(command(TaskCommandType.CREATE, "create"),
                new TaskChange(List.of(new SubTaskChange("patent", 0, SubTaskChange.Operation.ADD,
                        true, null, SubTaskChange.ResponsibilitySource.RULE, "rule:create", List.of(), null)),
                        Map.of(), Map.of()));
        version = 1;
    }

    private void apply(ResultFocusAction action) {
        TaskCommandType type = type(action);
        TaskCommand command = command(type, "focus-" + version);
        if (type == TaskCommandType.SELECT) service.select(command, focus(action));
        else service.continueTask(command, focus(action));
        version++;
    }

    private void reject(ResultFocusAction action) {
        assertThatThrownBy(() -> {
            if (type(action) == TaskCommandType.SELECT) {
                service.select(command(TaskCommandType.SELECT, "reject-" + version), focus(action));
            } else {
                service.continueTask(command(TaskCommandType.CONTINUE, "reject-" + version), focus(action));
            }
        })
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
        assertThat(service.snapshot("task").version()).isEqualTo(version);
    }

    private static TaskCommandType type(ResultFocusAction action) {
        return action.kind() == ResultFocusAction.Kind.SELECT
                || action.kind() == ResultFocusAction.Kind.BIND_EXPLICIT
                || action.kind() == ResultFocusAction.Kind.BIND_CURRENT
                || action.kind() == ResultFocusAction.Kind.BIND_LATEST_DELIVERED
                ? TaskCommandType.SELECT : TaskCommandType.CONTINUE;
    }

    private TaskCommand command(TaskCommandType type, String id) {
        return new TaskCommand(id, id, type, "task", version, Map.of(), Map.of(), null,
                Instant.parse("2026-09-23T00:00:00Z"));
    }

    private static TaskChange focus(ResultFocusAction action) {
        return new TaskChange(List.of(), Map.of(), Map.of(), action);
    }

    private static PatentResultSet.Candidate candidate(String id, String version) {
        return new PatentResultSet.Candidate(id, version);
    }

    private static ResultFocusAction produce(String branch, String id, long version, Long parent,
                                             String reason, List<PatentResultSet.Candidate> candidates) {
        PatentResultSet.Origin fixture = new PatentResultSet.Origin("fixture-scope", "fixture-method",
                List.of("source:fixture"), List.of("execution:fixture"), List.of("not-real-data"));
        return ResultFocusAction.produce(branch, id, version, parent, reason, fixture, candidates);
    }
}
