package cn.xinji.agentplatform.security.product;

import java.time.Instant;
import java.util.List;

/** Request facts only. This projection neither exposes authority internals nor claims current handling state. */
record ProductHumanRequestView(String kind, Receipt receipt, Body body) {
    record Receipt(String taskId, String packageId, long packageRevision, String manifestSha,
                   long eventSequence, String requestSha, Instant createdAt, Instant auditUntil) { }
    record Reference(String type, String id, long revision, String sha) {
        static Reference from(ProductHumanTaskFacts.Reference value) {
            return new Reference(value.type(), value.id(), value.revision(), value.sha());
        }
    }
    record Material(String sourceId, String documentId, String documentVersion, List<String> fields) { }
    record Body(long taskVersion, String reason, String purpose, Reference goal, Reference message, Reference input,
                List<Material> materials, String comparisonId, List<String> reportIds, List<String> gaps,
                Instant bodyAccessUntil) { }

    static ProductHumanRequestView from(ProductHumanRequestApplication.Result result) {
        var stored = result.receipt();
        var receipt = new Receipt(stored.taskId(), stored.packageId(), stored.packageRevision(), stored.manifestSha(),
                stored.eventSequence(), stored.requestSha(), stored.createdAt(), stored.auditUntil());
        if (result.body() == null) return new ProductHumanRequestView("RECEIPT_ONLY", receipt, null);
        var manifest = result.body().manifest(); var refs = manifest.references(); var facts = refs.facts();
        var materials = refs.materials().stream().map(material -> new Material(material.source().source().sourceId(),
                material.documentId(), material.documentVersion(), material.fieldLocators().keySet().stream().sorted().toList())).toList();
        var comparison = refs.comparison();
        var body = new Body(manifest.taskVersion(), manifest.reason(), manifest.purpose(), Reference.from(facts.goal()),
                Reference.from(facts.message()), Reference.from(facts.input()), materials,
                comparison == null ? null : comparison.id(), comparison == null ? List.of()
                    : comparison.reports().stream().map(ProductHumanRequestReferences.Report::reportId).toList(),
                refs.gaps().stream().map(Enum::name).toList(), ProductHumanRequestStore.bodyAccessUntil(manifest));
        return new ProductHumanRequestView("REQUEST_RECORD", receipt, body);
    }
}
