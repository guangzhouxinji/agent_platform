package cn.xinji.agentplatform.consultation.patentcomparison;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatentComparisonAncestralLineageTest {
    final RowKey a = new RowKey("A", List.of(1), "F1"), b = new RowKey("B", List.of(1), "F1");
    final Snapshot root = node("root", 1, null, facts(), Set.of(a, b));

    @Test void changingBTwicePreservesAOriginalCoordinatesWithoutAnyNewReport() {
        var parent = node("parent", 2, "root", changed(Type.CANDIDATE, "B", 2), Set.of(a, b));
        var current = node("current", 3, "parent", changed(Type.CANDIDATE, "B", 3), Set.of(a, b));
        var result = planAncestral(List.of(root, parent, current), report(root, a));
        assertThat(result.carried()).containsExactly(new CarryForwardFrom(a, "root", sha(1), "report-root-A", sha(90), sha(91)));
        assertThat(result.invalidated()).isEmpty(); assertThat(result.recompute()).containsExactly(b);
        var changedB = planAncestral(List.of(parent, current), report(parent, b));
        assertThat(changedB.carried()).isEmpty(); assertThat(changedB.invalidated()).containsExactly(b);
        assertThat(changedB.recompute()).containsExactlyInAnyOrder(a, b);
    }
    @Test void oneHopIsExactlyTheExistingPolicy() {
        var next = node("next", 2, "root", facts(), Set.of(a, b));
        assertThat(planAncestral(List.of(root, next), report(root, a)))
                .isEqualTo(plan(root, next, List.of(report(root, a))));
    }
    @Test void changedAndRestoredDependenciesNeverReviveAnInvalidatedReference() {
        for (var key : root.facts().keySet()) {
            if (key.equals(new FactKey(Type.CANDIDATE, "B"))) continue;
            var changed = new HashMap<>(root.facts()); changed.put(key, sha(9));
            var middle = node("middle", 2, "root", changed, Set.of(a, b));
            var restored = node("restored", 3, "middle", facts(), Set.of(a, b));
            assertThat(planAncestral(List.of(root, middle, restored), report(root, a))).satisfies(result -> {
                assertThat(result.carried()).isEmpty(); assertThat(result.invalidated()).containsExactly(a);
                assertThat(result.recompute()).containsExactlyInAnyOrder(a, b);
            });
        }
    }
    @Test void removedAndReaddedCandidateDoesNotReviveHistory() {
        var middleFacts = facts(); middleFacts.remove(new FactKey(Type.CANDIDATE, "A"));
        var middle = node("middle", 2, "root", middleFacts, Set.of(b));
        var restored = node("restored", 3, "middle", facts(), Set.of(a, b));
        assertThat(planAncestral(List.of(root, middle, restored), report(root, a)).carried()).isEmpty();
    }
    @Test void addedAndRemovedFeatureStillInvalidatesFullContext() {
        var middleFacts = facts(); middleFacts.put(new FactKey(Type.FEATURE, "F2"), sha(9));
        var middle = node("middle", 2, "root", middleFacts,
                Set.of(a, b, new RowKey("A", List.of(1), "F2"), new RowKey("B", List.of(1), "F2")));
        var restored = node("restored", 3, "middle", facts(), Set.of(a, b));
        assertThat(planAncestral(List.of(root, middle, restored), report(root, a)).invalidated()).containsExactly(a);
    }
    @Test void missingIntermediateConfigurationIsNotInferredFromEitherEndpoint() {
        var missing = facts(); missing.remove(new FactKey(Type.CONFIGURATION, "global"));
        assertThatThrownBy(() -> planAncestral(List.of(root,
                node("middle", 2, "root", missing, Set.of(a, b))), report(root, a)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void brokenLinksCrossTaskCyclesAndRepeatedHashesReject() {
        var middle = node("middle", 2, "root", facts(), Set.of(a, b));
        var wrong = node("current", 3, "wrong", facts(), Set.of(a, b));
        rejects(List.of(root, middle, wrong));
        rejects(List.of(root, new Snapshot("other", "middle", sha(2), "root", facts(), Set.of(a, b))));
        rejects(List.of(root, middle, node("root", 3, "middle", facts(), Set.of(a, b))));
        rejects(List.of(root, middle, node("current", 1, "middle", facts(), Set.of(a, b))));
        rejects(List.of(root, node("middle", 2, null, facts(), Set.of(a, b))));
    }
    @Test void sourceProofMustBelongToPathOriginAndBeComplete() {
        var next = node("next", 2, "root", facts(), Set.of(a, b));
        assertThatThrownBy(() -> planAncestral(List.of(root, next), report(next, a))).isInstanceOf(IllegalArgumentException.class);
        var source = report(root, a);
        var incomplete = new SourceReport(source.comparisonId(), source.inputSha(), "A", source.reportId(), source.reportSha(),
                FULL_FEATURE_CONTEXT, List.of(new RetainedRow(a, sha(91), Set.of(new FactKey(Type.CANDIDATE, "A")))));
        assertThatThrownBy(() -> planAncestral(List.of(root, next), incomplete)).hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        assertThatThrownBy(() -> planAncestral(List.of(root, next), null)).isInstanceOf(NullPointerException.class);
    }
    @Test void historyBudgetRejectsRatherThanSilentlyTruncating() {
        rejects(List.of()); rejects(List.of(root));
        List<Snapshot> path = new ArrayList<>(); path.add(root);
        for (int i = 2; i <= 64; i++) path.add(node("node" + i, i, path.getLast().comparisonId(), facts(), Set.of(a, b)));
        assertThat(planAncestral(path, report(root, a)).carried()).hasSize(1);
        path.add(node("node65", 65, "node64", facts(), Set.of(a, b))); rejects(path);
    }
    @Test void returnedReferencesAndCollectionsAreImmutableAndDoNotRewriteOriginals() {
        List<Snapshot> path = new ArrayList<>(List.of(root, node("next", 2, "root", facts(), Set.of(a, b))));
        var original = report(root, a); var result = planAncestral(path, original); path.clear();
        assertThat(result.carried()).hasSize(1); assertThat(original.comparisonId()).isEqualTo("root");
        assertThatThrownBy(() -> result.carried().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.invalidated().add(a)).isInstanceOf(UnsupportedOperationException.class);
    }
    private void rejects(List<Snapshot> path) {
        assertThatThrownBy(() -> planAncestral(path, report(root, a))).isInstanceOf(IllegalArgumentException.class);
    }
    private Snapshot node(String id, int digest, String previous, Map<FactKey, String> facts, Set<RowKey> rows) {
        return new Snapshot("task", id, sha(digest), previous, facts, rows);
    }
    private Map<FactKey, String> changed(Type type, String id, int value) {
        var result = facts(); result.put(new FactKey(type, id), sha(value)); return result;
    }
    private Map<FactKey, String> facts() {
        Map<FactKey, String> result = new HashMap<>();
        for (var type : List.of(Type.TARGET, Type.GOAL, Type.PURPOSE, Type.METHOD, Type.CONFIGURATION))
            result.put(new FactKey(type, "global"), sha(8));
        result.put(new FactKey(Type.FEATURE, "F1"), sha(8));
        for (var candidate : List.of("A", "B")) result.put(new FactKey(Type.CANDIDATE, candidate), sha(8));
        return result;
    }
    private SourceReport report(Snapshot input, RowKey row) {
        Set<FactKey> dependencies = new HashSet<>();
        for (var key : input.facts().keySet())
            if (key.type() != Type.CANDIDATE || key.id().equals(row.candidateId())) dependencies.add(key);
        return new SourceReport(input.comparisonId(), input.inputSha(), row.candidateId(), "report-" + input.comparisonId() + "-" + row.candidateId(),
                sha(90), FULL_FEATURE_CONTEXT, List.of(new RetainedRow(row, sha(91), dependencies)));
    }
    private static String sha(int value) { return String.format("%064x", value); }
}
