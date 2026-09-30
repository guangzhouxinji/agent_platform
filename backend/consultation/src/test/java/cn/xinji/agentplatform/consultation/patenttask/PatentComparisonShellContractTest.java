package cn.xinji.agentplatform.consultation.patenttask;

import cn.xinji.agentplatform.consultation.patenttask.adapter.out.memory.FixtureInMemoryPatentTaskCommandRepository;
import cn.xinji.agentplatform.consultation.patenttask.application.*;
import cn.xinji.agentplatform.consultation.patenttask.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PatentComparisonShellContractTest {
    private final PatentTaskCommandService tasks = new PatentTaskCommandService(
            new FixtureInMemoryPatentTaskCommandRepository(), action -> TaskAuthorizationDecision.allow("fixture-auth"));
    private long version;

    @Test void selectedDocumentVersionAndDirectLocatorReturnToOriginalTask() {
        setup();
        apply(PatentEvidenceAction.record(new PatentEvidenceAction.Evidence("excerpt", 1, "main", "fixture-source",
                "document-v1", "paragraph-8", "feature@1", true, List.of("development-only"))));
        PatentComparisonAction comparison = new PatentComparisonAction("comparison", 1, "main", "patent",
                "selected", "technical-reference", List.of(new PatentComparisonAction.Item("feature@1",
                "excerpt@1", PatentComparisonAction.Relationship.UNKNOWN, "requires professional review",
                List.of(), "fixture:structure")), List.of("not-a-legal-conclusion"));
        tasks.continueTask(command(TaskCommandType.CONTINUE, "compare"), new TaskChange(List.of(), Map.of(),
                Map.of(), null, null, null, null, comparison));
        version++;
        assertThat(tasks.snapshot("task").comparisonState().versions().get("comparison@1").items().get(0)
                .evidenceKey()).isEqualTo("excerpt@1");
        assertThat(tasks.snapshot("task").evidenceState().evidence().get("excerpt@1").locator())
                .isEqualTo("paragraph-8");
        assertThat(tasks.snapshot("task", version - 1).comparisonState().versions()).isEmpty();
    }

    @Test void unsupportedProfessionalJudgmentAndWrongDocumentVersionAreRejected() {
        setup();
        apply(PatentEvidenceAction.record(new PatentEvidenceAction.Evidence("wrong", 1, "main", "fixture-source",
                "document-other", "paragraph-1", "feature@1", true, List.of())));
        reject(new PatentComparisonAction.Item("feature@1", "wrong@1", PatentComparisonAction.Relationship.UNKNOWN,
                "wrong document", List.of(), "fixture:structure"));
        reject(new PatentComparisonAction.Item("feature@1", null, PatentComparisonAction.Relationship.SAME,
                "literal overlap", List.of(), "model:unapproved"));
    }

    @Test void correctionKeepsOldVersionButOnlyUnaffectedFeatureRemainsCurrent() {
        setup();
        register("other", PatentCorrectionAction.Type.FEATURE);
        var original = new PatentComparisonAction("comparison", 1, "main", "patent", "selected",
                "technical-reference", List.of(
                    new PatentComparisonAction.Item("feature@1", null, PatentComparisonAction.Relationship.UNKNOWN,
                            "missing excerpt", List.of("full text"), "fixture:structure"),
                    new PatentComparisonAction.Item("other@1", null, PatentComparisonAction.Relationship.UNKNOWN,
                            "missing excerpt", List.of("full text"), "fixture:structure")), List.of());
        tasks.continueTask(command(TaskCommandType.CONTINUE, "compare"), new TaskChange(List.of(), Map.of(),
                Map.of(), null, null, null, null, original));
        version++;
        tasks.correct(command(TaskCommandType.CORRECT, "correct"), new TaskChange(List.of(), Map.of(), Map.of(),
                null, new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                        new PatentCorrectionAction.Artifact("other", 2, PatentCorrectionAction.Type.FEATURE,
                                "main", List.of()), "other", 1, "user correction")));
        version++;
        var snapshot = tasks.snapshot("task");
        assertThat(snapshot.comparisonState().versions()).containsKey("comparison@1");
        assertThat(snapshot.comparisonState().currentItems("comparison@1", snapshot.resultFocus(),
                snapshot.correctionState(),
                snapshot.evidenceState())).extracting(PatentComparisonAction.Item::featureKey)
                .containsExactly("feature@1");
    }

    @Test void invalidatedSelectionCannotRemainAUsableComparison() {
        setup();
        var comparison = new PatentComparisonAction("comparison", 1, "main", "patent", "selected",
                "technical-reference", List.of(new PatentComparisonAction.Item("feature@1", null,
                PatentComparisonAction.Relationship.UNKNOWN, "missing text", List.of("full text"),
                "fixture:structure")), List.of());
        tasks.continueTask(command(TaskCommandType.CONTINUE, "compare"), new TaskChange(List.of(), Map.of(),
                Map.of(), null, null, null, null, comparison));
        version++;
        tasks.correct(command(TaskCommandType.CORRECT, "correct-scope"), new TaskChange(List.of(), Map.of(), Map.of(),
                null, new PatentCorrectionAction(PatentCorrectionAction.Kind.CORRECT,
                        new PatentCorrectionAction.Artifact("scope", 2, PatentCorrectionAction.Type.SCOPE,
                                "main", List.of()), "scope", 1, "range correction")));
        var current = tasks.snapshot("task");
        assertThat(current.comparisonState().currentItems("comparison@1", current.resultFocus(),
                current.correctionState(), current.evidenceState())).isEmpty();
        assertThat(current.comparisonState().versions()).containsKey("comparison@1");
    }

    private void setup() {
        tasks.create(new TaskCommand("create", "create", TaskCommandType.CREATE, "task", 0,
                Map.of(), Map.of(), null, Instant.parse("2026-09-23T00:00:00Z")), new TaskChange(List.of(
                new SubTaskChange("patent", 0, SubTaskChange.Operation.ADD, true, null,
                        SubTaskChange.ResponsibilitySource.RULE, "fixture:create", List.of(), null)), Map.of(), Map.of()));
        version = 1;
        register("scope", PatentCorrectionAction.Type.SCOPE);
        register("feature", PatentCorrectionAction.Type.FEATURE);
        var retrieval = new PatentRetrievalService(tasks, action -> TaskAuthorizationDecision.allow("fixture-search"),
                (scope, method, query) -> new PatentRetrievalPort.Response(PatentRetrievalAction.Outcome.RESULTS,
                        scope, method, List.of("source:fixture"),
                        List.of(new PatentResultSet.Candidate("candidate", "document-v1")),
                        List.of("development-only"), "fixture result"));
        retrieval.search(command(TaskCommandType.CONTINUE, "search"), new PatentRetrievalService.Request(
                "execution", "main", "scope-ref", "fixture-method", "query", "missing-candidate",
                null, "batch", 1, null, "scope@1", List.of("feature@1")));
        version++;
        tasks.continueTask(command(TaskCommandType.CONTINUE, "deliver"), new TaskChange(List.of(), Map.of(),
                Map.of(), ResultFocusAction.onResult(ResultFocusAction.Kind.DELIVER, "main", "batch", 1)));
        version++;
        tasks.select(command(TaskCommandType.SELECT, "select"), new TaskChange(List.of(), Map.of(), Map.of(),
                ResultFocusAction.bind("main", "batch", 1, 1, "selected")));
        version++;
    }

    private void register(String id, PatentCorrectionAction.Type type) {
        tasks.continueTask(command(TaskCommandType.CONTINUE, "register-" + id), new TaskChange(List.of(), Map.of(),
                Map.of(), null, new PatentCorrectionAction(PatentCorrectionAction.Kind.REGISTER,
                        new PatentCorrectionAction.Artifact(id, 1, type, "main", List.of()), null, 0, null)));
        version++;
    }

    private void apply(PatentEvidenceAction action) {
        tasks.continueTask(command(TaskCommandType.CONTINUE, "evidence-" + version),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, action));
        version++;
    }

    private void reject(PatentComparisonAction.Item item) {
        assertThatThrownBy(() -> tasks.continueTask(command(TaskCommandType.CONTINUE, "invalid-" + version),
                new TaskChange(List.of(), Map.of(), Map.of(), null, null, null, null,
                        new PatentComparisonAction("invalid", 1, "main", "patent", "selected",
                                "technical-reference", List.of(item), List.of()))))
                .isInstanceOfSatisfying(TaskCommandException.class,
                        error -> assertThat(error.code()).isEqualTo(TaskCommandException.Code.INVALID_COMMAND));
    }

    private TaskCommand command(TaskCommandType type, String id) {
        return new TaskCommand(id, id, type, "task", version, Map.of(), Map.of(), null,
                Instant.parse("2026-09-23T00:00:00Z"));
    }
}
