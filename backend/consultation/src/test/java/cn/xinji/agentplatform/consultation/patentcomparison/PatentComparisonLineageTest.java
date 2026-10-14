package cn.xinji.agentplatform.consultation.patentcomparison;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatentComparisonLineageTest {
    final RowKey a1 = new RowKey("A", List.of(1), "F1"), a2 = new RowKey("A", List.of(1), "F2");
    final RowKey b1 = new RowKey("B", List.of(1), "F1"), b2 = new RowKey("B", List.of(1), "F2");
    final Snapshot old = snapshot("old", "1", null, facts(), Set.of(a1, a2, b1, b2));

    @Test void candidateChangeKeepsOtherCandidateWithExactParentReferences() {
        var changed = facts(); changed.put(new FactKey(Type.CANDIDATE, "B"), sha("b"));
        var result = plan(old, next(changed, old.expectedRows()), List.of(report("B"), report("A")));
        assertThat(result.invalidated()).containsExactlyInAnyOrder(b1, b2);
        assertThat(result.recompute()).containsExactlyInAnyOrder(b1, b2);
        assertThat(result.carried()).extracting(CarryForwardFrom::key).containsExactly(a1, a2);
        assertThat(result.carried().getFirst()).isEqualTo(new CarryForwardFrom(a1, "old", sha("1"), "report-A", sha("3"), sha("4")));
    }
    @Test void ownFeatureUnchangedDoesNotProveIndependenceFromChangedOtherFeature() {
        var changed = facts(); changed.put(new FactKey(Type.FEATURE, "F2"), sha("b"));
        var result = plan(old, next(changed, old.expectedRows()), List.of(report("A"), report("B")));
        assertThat(result.invalidated()).containsExactlyInAnyOrder(a1, a2, b1, b2);
        assertThat(result.recompute()).isEqualTo(old.expectedRows()); assertThat(result.carried()).isEmpty();
    }
    @Test void addedFeatureChangesFullContextAndNewRowsNeedCalculation() {
        var changed = facts(); changed.put(new FactKey(Type.FEATURE, "F3"), sha("b"));
        var rows = new HashSet<>(old.expectedRows()); rows.add(new RowKey("A", List.of(1), "F3")); rows.add(new RowKey("B", List.of(1), "F3"));
        var result = plan(old, next(changed, rows), List.of(report("A"), report("B")));
        assertThat(result.invalidated()).isEqualTo(old.expectedRows()); assertThat(result.recompute()).isEqualTo(rows);
        assertThat(result.carried()).isEmpty();
    }
    @Test void removedCandidateKeepsHistoryReferenceButDoesNotRecomputeDeletedRows() {
        var changed = facts(); changed.remove(new FactKey(Type.CANDIDATE, "B"));
        var result = plan(old, next(changed, Set.of(a1, a2)), List.of(report("A"), report("B")));
        assertThat(result.invalidated()).containsExactlyInAnyOrder(b1, b2); assertThat(result.recompute()).isEmpty();
        assertThat(old.expectedRows()).contains(b1, b2); assertThat(result.carried()).hasSize(2);
    }
    @Test void changedGlobalMethodTargetGoalPurposeOrConfigurationInvalidatesAllConsumedRows() {
        for (Type type : List.of(Type.TARGET, Type.GOAL, Type.PURPOSE, Type.METHOD, Type.CONFIGURATION)) {
            var changed = facts(); changed.put(new FactKey(type, "global"), sha("b"));
            assertThat(plan(old, next(changed, old.expectedRows()), List.of(report("A"), report("B")))).satisfies(result -> {
                assertThat(result.invalidated()).isEqualTo(old.expectedRows()); assertThat(result.carried()).isEmpty();
            });
        }
    }
    @Test void explicitlyRecordedExtraDependencyIsNotDropped() {
        var original = report("A"); var deps = new HashSet<>(original.rows().getFirst().dependencies());
        deps.add(new FactKey(Type.CANDIDATE, "B"));
        var row = new RetainedRow(a1, sha("4"), deps);
        var report = replaceRows(original, List.of(row, original.rows().getLast()));
        var changed = facts(); changed.put(new FactKey(Type.CANDIDATE, "B"), sha("b"));
        var result = plan(old, next(changed, old.expectedRows()), List.of(report));
        assertThat(result.invalidated()).containsExactly(a1); assertThat(result.carried()).extracting(CarryForwardFrom::key).containsExactly(a2);
    }
    @Test void legacyOrIncompleteDependencyEvidenceCannotAuthorizeCarryForward() {
        var source = report("A"); var after = next(facts(), old.expectedRows());
        assertThatThrownBy(() -> plan(old, after, List.of(new SourceReport("old", sha("1"), "A", "report-A", sha("3"), null, source.rows()))))
                .hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        var deps = new HashSet<>(source.rows().getFirst().dependencies()); deps.remove(new FactKey(Type.FEATURE, "F2"));
        assertThatThrownBy(() -> plan(old, after, List.of(replaceRows(source, List.of(new RetainedRow(a1, sha("4"), deps), source.rows().getLast())))))
                .hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
        assertThatThrownBy(() -> plan(old, after, List.of(replaceRows(source, List.of(new RetainedRow(a1, sha("4"), null), source.rows().getLast())))))
                .hasMessage("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED");
    }
    @Test void wrongInputScopeDuplicateReportAndIncompleteRowDomainReject() {
        var after = next(facts(), old.expectedRows()); var source = report("A");
        assertThatThrownBy(() -> plan(old, after, List.of(source, source))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, after, List.of(replaceRows(source, List.of(source.rows().getFirst()))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, after, List.of(replaceRows(source, List.of(source.rows().getFirst(), source.rows().getFirst()))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, after, List.of(new SourceReport("other", sha("1"), "A", "report-A", sha("3"), FULL_FEATURE_CONTEXT, source.rows())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, snapshot("new", "2", "wrong", facts(), old.expectedRows()), List.of(source))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, new Snapshot("other-task", "new", sha("2"), "old", facts(), old.expectedRows()), List.of(source))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> plan(old, snapshot("new", "1", "old", facts(), old.expectedRows()), List.of(source))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownDependencyCannotBeSmuggledIntoAnOtherwiseCompleteProof() {
        var source = report("A"); var deps = new HashSet<>(source.rows().getFirst().dependencies());
        deps.add(new FactKey(Type.FEATURE, "missing"));
        assertThatThrownBy(() -> plan(old, next(facts(), old.expectedRows()), List.of(replaceRows(source,
                List.of(new RetainedRow(a1, sha("4"), deps), source.rows().getLast()))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void noParentReportsMeansPendingWorkNotInventedFailureOrCompletion() {
        var result = plan(old, next(facts(), old.expectedRows()), List.of());
        assertThat(result.invalidated()).isEmpty(); assertThat(result.carried()).isEmpty(); assertThat(result.recompute()).isEqualTo(old.expectedRows());
    }
    @Test void snapshotsAndResultsAreImmutableAndDoNotRewriteSourceCollections() {
        var mutable = facts(); var rows = new HashSet<>(old.expectedRows()); var after = next(mutable, rows);
        mutable.clear(); rows.clear(); var result = plan(old, after, List.of(report("A"), report("B")));
        assertThat(result.carried()).hasSize(4); assertThat(result.invalidated()).isEmpty(); assertThat(result.recompute()).isEmpty();
        assertThatThrownBy(() -> result.carried().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.recompute().add(a1)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void malformedDigestsBranchesAndBudgetsReject() {
        assertThatThrownBy(() -> new RowKey("A", List.of(2, 1), "F1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot("new", "x", "old", facts(), old.expectedRows())).isInstanceOf(IllegalArgumentException.class);
        var tooMany = facts(); for (int i = 3; i <= 201; i++) tooMany.put(new FactKey(Type.FEATURE, "F" + i), sha("a"));
        assertThatThrownBy(() -> next(tooMany, old.expectedRows())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotRejectsOmittedFeatureAndCandidateRows() {
        assertThatThrownBy(() -> next(facts(), Set.of(a1, b1, b2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> next(facts(), Set.of(a1, a2))).isInstanceOf(IllegalArgumentException.class);
    }
    private Snapshot next(Map<FactKey, String> facts, Set<RowKey> rows) { return snapshot("new", "2", "old", facts, rows); }
    private Snapshot snapshot(String id, String digest, String previous, Map<FactKey, String> facts, Set<RowKey> rows) {
        return new Snapshot("task", id, sha(digest), previous, facts, rows);
    }
    private Map<FactKey, String> facts() {
        var facts = new HashMap<FactKey, String>();
        for (Type type : List.of(Type.TARGET, Type.GOAL, Type.PURPOSE, Type.METHOD, Type.CONFIGURATION)) facts.put(new FactKey(type, "global"), sha("a"));
        for (String feature : List.of("F1", "F2")) facts.put(new FactKey(Type.FEATURE, feature), sha("a"));
        for (String candidate : List.of("A", "B")) facts.put(new FactKey(Type.CANDIDATE, candidate), sha("a"));
        return facts;
    }
    private SourceReport report(String candidate) {
        var deps = new HashSet<FactKey>();
        for (FactKey key : old.facts().keySet()) if (key.type() != Type.CANDIDATE || key.id().equals(candidate)) deps.add(key);
        var rows = old.expectedRows().stream().filter(key -> key.candidateId().equals(candidate))
                .sorted(Comparator.comparing(RowKey::featureId)).map(key -> new RetainedRow(key, sha("4"), deps)).toList();
        return new SourceReport("old", sha("1"), candidate, "report-" + candidate, sha("3"), FULL_FEATURE_CONTEXT, rows);
    }
    private SourceReport replaceRows(SourceReport source, List<RetainedRow> rows) {
        return new SourceReport(source.comparisonId(), source.inputSha(), source.candidateId(), source.reportId(), source.reportSha(), source.dependencyVersion(), rows);
    }
    private static String sha(String character) { return character.repeat(64); }
}
