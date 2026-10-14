package cn.xinji.agentplatform.consultation.patentcomparison;

import java.util.*;

/** Mechanical lineage only. Application ports authenticate originals and recheck current authority. */
public final class PatentComparisonLineage {
    public static final String FULL_FEATURE_CONTEXT = "FULL_FEATURE_CONTEXT_V1";
    public enum Type { TARGET, GOAL, PURPOSE, METHOD, CONFIGURATION, FEATURE, CANDIDATE }
    private static final Set<Type> GLOBALS = Set.of(Type.TARGET, Type.GOAL, Type.PURPOSE, Type.METHOD, Type.CONFIGURATION);

    public record FactKey(Type type, String id) {
        public FactKey { Objects.requireNonNull(type); text(id); }
    }
    public record RowKey(String candidateId, List<Integer> claimBranch, String featureId) {
        public RowKey {
            text(candidateId); text(featureId); claimBranch = List.copyOf(claimBranch);
            if (claimBranch.isEmpty()) throw invalid();
            int previous = 0;
            for (int number : claimBranch) { if (number <= previous) throw invalid(); previous = number; }
        }
    }
    public record Snapshot(String taskId, String comparisonId, String inputSha, String previousComparisonId,
                           Map<FactKey, String> facts, Set<RowKey> expectedRows) {
        public Snapshot {
            text(taskId); text(comparisonId); hash(inputSha);
            if (previousComparisonId != null) text(previousComparisonId);
            facts = Map.copyOf(facts); expectedRows = Set.copyOf(expectedRows);
            facts.values().forEach(PatentComparisonLineage::hash);
            for (Type type : GLOBALS)
                if (!facts.containsKey(new FactKey(type, "global")) || keys(facts, type).size() != 1) throw invalid();
            int features = keys(facts, Type.FEATURE).size(), candidates = keys(facts, Type.CANDIDATE).size();
            if (features < 1 || features > 200 || candidates < 1 || candidates > 20
                    || expectedRows.isEmpty() || expectedRows.size() > 4000) throw invalid();
            for (RowKey row : expectedRows)
                if (!facts.containsKey(new FactKey(Type.FEATURE, row.featureId()))
                        || !facts.containsKey(new FactKey(Type.CANDIDATE, row.candidateId()))) throw invalid();
            for (FactKey candidate : keys(facts, Type.CANDIDATE)) {
                Set<List<Integer>> branches = new HashSet<>();
                for (RowKey row : expectedRows) if (candidate.id().equals(row.candidateId())) branches.add(row.claimBranch());
                if (branches.isEmpty()) throw invalid();
                for (List<Integer> branch : branches) for (FactKey feature : keys(facts, Type.FEATURE))
                    if (!expectedRows.contains(new RowKey(candidate.id(), branch, feature.id()))) throw invalid();
            }
        }
    }
    public record RetainedRow(RowKey key, String rowSha, Set<FactKey> dependencies) {
        public RetainedRow {
            Objects.requireNonNull(key); hash(rowSha);
            dependencies = dependencies == null ? null : Set.copyOf(dependencies);
        }
    }
    public record SourceReport(String comparisonId, String inputSha, String candidateId, String reportId,
                               String reportSha, String dependencyVersion, List<RetainedRow> rows) {
        public SourceReport {
            text(comparisonId); hash(inputSha); text(candidateId); text(reportId); hash(reportSha);
            rows = List.copyOf(rows);
            if (rows.isEmpty() || rows.size() > 4000) throw invalid();
        }
    }
    public record CarryForwardFrom(RowKey key, String comparisonId, String inputSha, String reportId,
                                   String reportSha, String rowSha) { }
    public record Plan(Set<RowKey> invalidated, Set<RowKey> recompute, List<CarryForwardFrom> carried) {
        public Plan {
            invalidated = Set.copyOf(invalidated); recompute = Set.copyOf(recompute); carried = List.copyOf(carried);
        }
    }

    private PatentComparisonLineage() { }

    /** Authenticated snapshots only; authority, configuration provenance and TTL remain application obligations. */
    public static Plan planAncestral(List<Snapshot> path, SourceReport original) {
        path = List.copyOf(path);
        if (path.size() < 2 || path.size() > 64) throw invalid();
        Set<String> ids = new HashSet<>(), shas = new HashSet<>();
        for (int i = 0; i < path.size(); i++) {
            var node = path.get(i);
            if (!ids.add(node.comparisonId()) || !shas.add(node.inputSha())) throw invalid();
            if (i > 0) requireSuccessor(path.get(i - 1), node);
        }
        var first = plan(path.getFirst(), path.get(1), List.of(original));
        Set<RowKey> invalidated = new HashSet<>(first.invalidated());
        Map<RowKey, RetainedRow> rows = new HashMap<>();
        for (var row : original.rows()) rows.put(row.key(), row);
        List<CarryForwardFrom> carried = new ArrayList<>(first.carried());
        for (int i = 2; i < path.size(); i++) {
            var before = path.get(i - 1); var after = path.get(i);
            // Once removed, a reference is never reconsidered even if a later version restores the facts.
            carried.removeIf(ref -> {
                if (!rowChanged(before, after, rows.get(ref.key()))) return false;
                invalidated.add(ref.key()); return true;
            });
        }
        Set<RowKey> recompute = new HashSet<>(path.getLast().expectedRows());
        for (var ref : carried) recompute.remove(ref.key());
        return new Plan(invalidated, recompute, carried);
    }

    public static Plan plan(Snapshot before, Snapshot after, List<SourceReport> originals) {
        Objects.requireNonNull(before); Objects.requireNonNull(after); originals = List.copyOf(originals);
        requireSuccessor(before, after);
        if (originals.size() > 20) throw invalid();
        Set<RowKey> invalidated = new HashSet<>(), carriedKeys = new HashSet<>();
        List<CarryForwardFrom> carried = new ArrayList<>();
        Set<String> candidates = new HashSet<>(), reportIds = new HashSet<>();
        for (SourceReport report : originals) {
            if (!before.comparisonId().equals(report.comparisonId()) || !before.inputSha().equals(report.inputSha())
                    || !candidates.add(report.candidateId()) || !reportIds.add(report.reportId())) throw invalid();
            if (!FULL_FEATURE_CONTEXT.equals(report.dependencyVersion())) throw missingEvidence();
            Set<RowKey> expected = new HashSet<>();
            for (RowKey key : before.expectedRows()) if (report.candidateId().equals(key.candidateId())) expected.add(key);
            Set<RowKey> seen = new HashSet<>();
            Set<FactKey> required = new HashSet<>(keys(before.facts(), Type.FEATURE));
            for (Type type : GLOBALS) required.add(new FactKey(type, "global"));
            required.add(new FactKey(Type.CANDIDATE, report.candidateId()));
            for (RetainedRow row : report.rows()) {
                if (!expected.contains(row.key()) || !seen.add(row.key())) throw invalid();
                if (row.dependencies() == null || !row.dependencies().containsAll(required)) throw missingEvidence();
                if (!before.facts().keySet().containsAll(row.dependencies())) throw invalid();
                boolean changed = rowChanged(before, after, row);
                if (changed) invalidated.add(row.key());
                else {
                    carriedKeys.add(row.key());
                    carried.add(new CarryForwardFrom(row.key(), report.comparisonId(), report.inputSha(),
                            report.reportId(), report.reportSha(), row.rowSha()));
                }
            }
            if (!seen.equals(expected)) throw invalid();
        }
        Set<RowKey> recompute = new HashSet<>(after.expectedRows()); recompute.removeAll(carriedKeys);
        carried.sort(Comparator.comparing((CarryForwardFrom ref) -> ref.key().candidateId())
                .thenComparing(ref -> ref.key().claimBranch(), PatentComparisonLineage::compareBranches)
                .thenComparing(ref -> ref.key().featureId()));
        return new Plan(invalidated, recompute, carried);
    }

    private static void requireSuccessor(Snapshot before, Snapshot after) {
        if (!before.taskId().equals(after.taskId()) || before.comparisonId().equals(after.comparisonId())
                || before.inputSha().equals(after.inputSha()) || !before.comparisonId().equals(after.previousComparisonId())
                || after.comparisonId().equals(before.previousComparisonId())) throw invalid();
    }
    private static boolean rowChanged(Snapshot before, Snapshot after, RetainedRow row) {
        // Adding a feature changes the full request even though no old edge names the new feature.
        return !keys(before.facts(), Type.FEATURE).equals(keys(after.facts(), Type.FEATURE))
                || !after.expectedRows().contains(row.key())
                || row.dependencies().stream().anyMatch(k -> !before.facts().containsKey(k)
                        || !before.facts().get(k).equals(after.facts().get(k)));
    }
    private static Set<FactKey> keys(Map<FactKey, String> facts, Type type) {
        Set<FactKey> keys = new HashSet<>();
        for (FactKey key : facts.keySet()) if (key.type() == type) keys.add(key);
        return keys;
    }
    private static int compareBranches(List<Integer> left, List<Integer> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int order = Integer.compare(left.get(i), right.get(i)); if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 200) throw invalid();
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("LINEAGE_BINDING_OR_DOMAIN_INVALID"); }
    private static IllegalStateException missingEvidence() { return new IllegalStateException("LINEAGE_DEPENDENCY_EVIDENCE_REQUIRED"); }
}
