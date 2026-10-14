package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import java.util.*;

/** Byte binding only. The owning transaction must authenticate invocation, receipt and current rights. */
final class ProductComparisonLineageCodec {
    private static final String VERSION = "DEPENDENCY_ORIGINAL_V1";
    record Fact(FactKey key, String sha) { }
    record Row(RowKey key, String sha, List<FactKey> dependencies) { }
    record Original(String version, String dependencyVersion, String taskId, String comparisonId, String inputSha,
                    String candidateId, String reportId, String reportSha, String requestSha, List<Fact> facts, List<Row> rows) { }
    record Encoded(String raw, String sha) { }
    private final ProductComparisonJson json;
    ProductComparisonLineageCodec(ProductComparisonJson json) { this.json = json; }

    Encoded encode(ProductComparisonLineageFacts.Captured captured, String reportRaw, String reportSha) {
        String raw = json.canonical(original(captured, reportRaw, reportSha));
        return new Encoded(raw, ProductComparisonJson.hash(raw));
    }
    SourceReport decode(Encoded encoded, ProductComparisonLineageFacts.Captured captured, String reportRaw, String reportSha) {
        if (encoded == null || encoded.raw() == null || !ProductComparisonJson.hash(encoded.raw()).equals(encoded.sha())) throw invalid();
        var value = json.stored(encoded.raw(), Original.class);
        if (!original(captured, reportRaw, reportSha).equals(value)) throw invalid();
        return new SourceReport(value.comparisonId(), value.inputSha(), value.candidateId(), value.reportId(), value.reportSha(),
                value.dependencyVersion(), value.rows().stream().map(row -> new RetainedRow(row.key(), row.sha(), Set.copyOf(row.dependencies()))).toList());
    }
    private Original original(ProductComparisonLineageFacts.Captured captured, String reportRaw, String reportSha) {
        if (captured == null || captured.requestSha() == null || !captured.requestSha().matches("[a-f0-9]{64}")
                || reportRaw == null || !ProductComparisonJson.hash(reportRaw).equals(reportSha)) throw invalid();
        var report = json.stored(reportRaw, ProductComparisonMatrixResults.Package.class); var snapshot = captured.snapshot();
        if (!snapshot.taskId().equals(report.taskId()) || !snapshot.comparisonId().equals(report.comparisonId())
                || !snapshot.inputSha().equals(report.inputSha()) || report.reportId() == null || report.reportId().isBlank()
                || !"PATENT_FEATURE_MATRIX_V1".equals(report.methodVersion())
                || report.rows() == null || report.rows().isEmpty() || report.rows().size() > 4000) throw invalid();
        var order = Comparator.comparing((FactKey key) -> key.type().name()).thenComparing(FactKey::id);
        var facts = snapshot.facts().entrySet().stream().sorted(Map.Entry.comparingByKey(order))
                .map(entry -> new Fact(entry.getKey(), entry.getValue())).toList();
        var dependencies = snapshot.facts().keySet().stream()
                .filter(key -> key.type() != Type.CANDIDATE || key.id().equals(report.candidateId())).sorted(order).toList();
        Set<RowKey> expected = new HashSet<>(), seen = new HashSet<>();
        for (RowKey row : snapshot.expectedRows()) if (row.candidateId().equals(report.candidateId())) expected.add(row);
        List<Row> rows = new ArrayList<>();
        for (var row : report.rows()) {
            var key = new RowKey(row.candidateId(), row.claimBranch(), row.featureId());
            if (!expected.contains(key) || !seen.add(key)) throw invalid();
            rows.add(new Row(key, json.sha(row), dependencies));
        }
        if (!seen.equals(expected)) throw invalid();
        rows.sort(Comparator.comparing(row -> json.canonical(row.key())));
        return new Original(VERSION, FULL_FEATURE_CONTEXT, snapshot.taskId(), snapshot.comparisonId(), snapshot.inputSha(),
                report.candidateId(), report.reportId(), reportSha, captured.requestSha(), facts, List.copyOf(rows));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("LINEAGE_ORIGINAL_BINDING_INVALID"); }
}
