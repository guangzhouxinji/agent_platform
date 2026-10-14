package cn.xinji.agentplatform.security.product;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable coordinates only. The request assembler authenticates every referenced object. */
record ProductHumanRequestReferences(ProductHumanTaskFacts.Snapshot facts,
        List<ProductHumanMaterialReferences.Reference> materials, List<SourcePin> sources, Comparison comparison,
        ProductHumanCleanupScope.Snapshot cleanup, List<Gap> gaps) {
    enum Gap { NO_COMPARISON_SELECTED, NO_REPORT_SELECTED }
    record SourcePin(ProductHumanRequestLocks.SourceUse use, ProductSourceAvailability.Active source,
                     String scopeId, long scopeVersion, ProductHumanRequestLocks.Rights rights) {
        SourcePin {
            Objects.requireNonNull(use); Objects.requireNonNull(source); Objects.requireNonNull(rights);
            if (!use.sourceId().equals(source.sourceId()) || blank(scopeId) || scopeVersion < 1) throw invalid();
        }
    }
    record Report(String reportId, long revision, String sha, String candidateId,
                  String comparisonId, String inputSha, Instant expiresAt,
                  List<ProductComparisonParentEvidenceReader.Ancestor> ancestry) {
        Report {
            if (blank(reportId) || revision < 1 || !digest(sha) || blank(candidateId)
                    || blank(comparisonId) || !digest(inputSha)) throw invalid();
            Objects.requireNonNull(expiresAt); ancestry = List.copyOf(ancestry);
        }
    }
    record Comparison(String id, String sha, long taskVersion, String goalId, long goalRevision,
                      String searchInputId, Instant expiresAt, List<Report> reports) {
        Comparison {
            if (blank(id) || !digest(sha) || taskVersion < 1 || blank(goalId)
                    || goalRevision < 1 || blank(searchInputId)) throw invalid();
            Objects.requireNonNull(expiresAt); reports = List.copyOf(reports);
            if (reports.stream().map(Report::reportId).distinct().count() != reports.size()) throw invalid();
        }
    }
    ProductHumanRequestReferences {
        Objects.requireNonNull(facts); Objects.requireNonNull(cleanup);
        materials = List.copyOf(materials); sources = List.copyOf(sources); gaps = List.copyOf(gaps);
        if (!facts.taskId().equals(cleanup.taskId()) || !"READY".equals(cleanup.state())
                || cleanup.intent() != null || cleanup.generation() < 1) throw invalid();
        var expected = comparison == null ? List.of(Gap.NO_COMPARISON_SELECTED, Gap.NO_REPORT_SELECTED)
                : comparison.reports().isEmpty() ? List.of(Gap.NO_REPORT_SELECTED) : List.<Gap>of();
        if (!gaps.equals(expected)) throw invalid();
        if (comparison != null && (comparison.taskVersion() != facts.taskVersion()
                || !comparison.goalId().equals(facts.goal().id()) || comparison.goalRevision() != facts.goal().revision()
                || !comparison.searchInputId().equals(facts.input().id()))) throw invalid();
    }
    List<String> coordinates() {
        var result = new ArrayList<String>();
        for (var ref : List.of(facts.goal(), facts.message(), facts.input()))
            result.add(ref.type() + ":" + ref.id() + ":" + ref.revision() + ":" + ref.sha());
        for (var ref : materials) result.add("SOURCE_DOCUMENT:" + ref.source().source().sourceId()
                + ":" + ref.documentId() + ":" + ref.documentVersion() + ":" + ref.rawSha());
        if (comparison != null) {
            result.add("COMPARISON_INPUT:" + comparison.id() + ":" + comparison.sha());
            for (var report : comparison.reports()) result.add("REPORT:" + report.reportId() + ":" + report.revision() + ":" + report.sha());
        }
        return List.copyOf(result);
    }
    cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.Binding binding() {
        var input = comparison == null ? facts.input().id() + ":" + facts.input().sha()
                : comparison.id() + ":" + comparison.sha();
        String report = comparison == null || comparison.reports().isEmpty() ? null : "REPORT_SET:"
                + ProductComparisonJson.hash(comparison.reports().stream().map(r -> r.reportId() + ":" + r.revision() + ":" + r.sha())
                    .sorted().collect(java.util.stream.Collectors.joining("\n")));
        return new cn.xinji.agentplatform.consultation.patenttask.human.OrdinaryHumanHandling.Binding(
                facts.goal().id() + ":" + facts.goal().revision(), facts.message().id() + ":" + facts.message().sha(), input, report);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Exact typed human references required"); }
}
