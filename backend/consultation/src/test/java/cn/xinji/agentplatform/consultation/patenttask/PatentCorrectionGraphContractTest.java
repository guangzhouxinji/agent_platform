package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentCorrectionGraphContractTest {
    private final PatentTaskCommandService service = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(), action -> TaskAuthorizationDecision.allow("fixture-auth"));
    private long version;

    @Test void featureCorrectionInvalidatesOnlyTransitiveDependentsAndKeepsHistory() {
        create();
        register("feature", 1, PatentCorrectionAction.Type.FEATURE, "main");
        register("query", 1, PatentCorrectionAction.Type.QUERY, "main", "feature@1");
        register("comparison", 1, PatentCorrectionAction.Type.COMPARISON_ITEM, "main", "query@1");
        register("unrelated", 1, PatentCorrectionAction.Type.SOURCE_FACT, "main");
        correct("feature", 1, 2, PatentCorrectionAction.Type.FEATURE, "main");
        assertImpact("feature@1", "query@1", "comparison@1");
        assertThat(state().usable("unrelated@1")).isTrue();
        assertThat(state().usable("feature@2")).isTrue();
        assertThat(service.snapshot("task", version - 1).correctionState().usable("query@1")).isTrue();
        assertThat(service.snapshot("task").events().get((int) version - 1).relatedArtifactReferences())
                .contains("comparison@1");
    }

    @Test void scopeCorrectionInvalidatesResultButKeepsUnrelatedSourceAndOtherBranch() {
        create();
        register("scope", 1, PatentCorrectionAction.Type.SCOPE, "main");
        ResultFocusAction produced = ResultFocusAction.produce("main", "batch", 1, null, "fixture-search",
                new PatentResultSet.Origin("fixture-scope", "fixture-method", List.of("source:fixture"),
                        List.of("execution:fixture"), List.of("development-only")),
                List.of(new PatentResultSet.Candidate("candidate", "document-v1")));
        service.continueTask(command(TaskCommandType.CONTINUE, "produce-" + version),
                new TaskChange(List.of(), Map.of(), Map.of(), produced));
        version++;
        service.continueTask(command(TaskCommandType.CONTINUE, "deliver-" + version), new TaskChange(
                List.of(), Map.of(), Map.of(), ResultFocusAction.onResult(
                ResultFocusAction.Kind.DELIVER, "main", "batch", 1)));
        version++;
        service.select(command(TaskCommandType.SELECT, "select-" + version), new TaskChange(
                List.of(), Map.of(), Map.of(), ResultFocusAction.onResult(
                ResultFocusAction.Kind.SELECT, "main", "batch", 1)));
        version++;
        register("result:batch", 1, PatentCorrectionAction.Type.RESULT_SET, "main", "scope@1");
        register("source", 1, PatentCorrectionAction.Type.SOURCE_FACT, "main");
        register("other", 1, PatentCorrectionAction.Type.SOURCE_FACT, "other");
        correct("scope", 1, 2, PatentCorrectionAction.Type.SCOPE, "main");
        assertImpact("scope@1", "result:batch@1");
        assertThat(service.snapshot("task").resultFocus().currentFocus("main").available()).isFalse();
        assertThat(state().usable("source@1")).isTrue();
        assertThat(state().usable("other@1")).isTrue();
    }

    @Test void candidateCorrectionRecomputesOnlyItsSelectionAndComparison() {
        create();
        register("candidate", 1, PatentCorrectionAction.Type.CANDIDATE, "main");
        register("selection", 1, PatentCorrectionAction.Type.SELECTION_REASON, "main", "candidate@1");
        register("comparison", 1, PatentCorrectionAction.Type.COMPARISON_ITEM, "main", "selection@1");
        register("query", 1, PatentCorrectionAction.Type.QUERY, "main");
        correct("candidate", 1, 2, PatentCorrectionAction.Type.CANDIDATE, "main");
        assertImpact("candidate@1", "selection@1", "comparison@1");
        assertThat(state().usable("query@1")).isTrue();
    }

    @Test void referenceCorrectionDoesNotRewriteTheResultSnapshot() {
        create();
        register("result:batch", 1, PatentCorrectionAction.Type.RESULT_SET, "main");
        register("reference", 1, PatentCorrectionAction.Type.REFERENCE, "main", "result:batch@1");
        register("answer", 1, PatentCorrectionAction.Type.ANSWER_CLAIM, "main", "reference@1");
        correct("reference", 1, 2, PatentCorrectionAction.Type.REFERENCE, "main", "result:batch@1");
        assertImpact("reference@1", "answer@1");
        assertThat(state().usable("result:batch@1")).isTrue();
    }

    @Test void goalChangeRetainsIndependentFactsWhileObjectChangeInvalidatesItsQuery() {
        create();
        register("goal", 1, PatentCorrectionAction.Type.GOAL, "main");
        register("object", 1, PatentCorrectionAction.Type.OBJECT, "main");
        register("goal-answer", 1, PatentCorrectionAction.Type.ANSWER_CLAIM, "main", "goal@1");
        register("object-query", 1, PatentCorrectionAction.Type.QUERY, "main", "object@1");
        correct("goal", 1, 2, PatentCorrectionAction.Type.GOAL, "main");
        assertThat(state().usable("object-query@1")).isTrue();
        correct("object", 1, 2, PatentCorrectionAction.Type.OBJECT, "main");
        assertThat(state().usable("object-query@1")).isFalse();
        assertThat(state().usable("goal@2")).isTrue();
    }

    @Test void staleDependencyCrossBranchAndPayloadReuseAreRejected() {
        create();
        register("feature", 1, PatentCorrectionAction.Type.FEATURE, "main");
        assertThatThrownBy(() -> register("foreign", 1, PatentCorrectionAction.Type.QUERY, "other", "feature@1"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
        TaskCommand command = command(TaskCommandType.CORRECT, "same-command");
        PatentCorrectionAction first = correction("feature", 1, 2, PatentCorrectionAction.Type.FEATURE, "main");
        service.correct(command, change(first));
        version++;
        assertThat(service.correct(command, change(first)).outcome()).isEqualTo(TaskCommandResult.Outcome.REPLAYED);
        assertThatThrownBy(() -> service.correct(command, change(new PatentCorrectionAction(
                PatentCorrectionAction.Kind.CORRECT, first.artifact(), "feature", 1, "different reason"))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.PAYLOAD_CONFLICT));
        assertThatThrownBy(() -> register("late", 1, PatentCorrectionAction.Type.QUERY, "main", "feature@1"))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
    }

    private void create() {
        service.create(command(TaskCommandType.CREATE, "create"), new TaskChange(List.of(
                new SubTaskChange("patent", 0, SubTaskChange.Operation.ADD, true, null,
                        SubTaskChange.ResponsibilitySource.RULE, "fixture:create", List.of(), null)), Map.of(), Map.of()));
        version = 1;
    }

    private void register(String id, long artifactVersion, PatentCorrectionAction.Type type,
                          String branch, String... dependencies) {
        service.continueTask(command(TaskCommandType.CONTINUE, "register-" + version), change(
                new PatentCorrectionAction(PatentCorrectionAction.Kind.REGISTER,
                        new PatentCorrectionAction.Artifact(id, artifactVersion, type, branch,
                                List.of(dependencies)), null, 0, null)));
        version++;
    }

    private void correct(String id, long oldVersion, long newVersion, PatentCorrectionAction.Type type,
                         String branch, String... dependencies) {
        service.correct(command(TaskCommandType.CORRECT, "correct-" + version),
                change(correction(id, oldVersion, newVersion, type, branch, dependencies)));
        version++;
    }

    private PatentCorrectionAction correction(String id, long oldVersion, long newVersion,
                                               PatentCorrectionAction.Type type, String branch,
                                               String... dependencies) {
        return new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                new PatentCorrectionAction.Artifact(id, newVersion, type, branch, List.of(dependencies)),
                id, oldVersion, "user correction");
    }

    private void assertImpact(String... invalidated) {
        PatentCorrectionState.Impact impact = state().impacts().get(state().impacts().size() - 1);
        assertThat(impact.invalidated()).containsExactlyInAnyOrder(invalidated);
        assertThat(impact.recomputeActions()).hasSize(invalidated.length - 1);
    }

    private PatentCorrectionState state() { return service.snapshot("task").correctionState(); }

    private TaskChange change(PatentCorrectionAction action) {
        return new TaskChange(List.of(), Map.of(), Map.of(), null, action);
    }

    private TaskCommand command(TaskCommandType type, String id) {
        return new TaskCommand(id, id, type, "task", type == TaskCommandType.CREATE ? 0 : version,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z"));
    }
}
