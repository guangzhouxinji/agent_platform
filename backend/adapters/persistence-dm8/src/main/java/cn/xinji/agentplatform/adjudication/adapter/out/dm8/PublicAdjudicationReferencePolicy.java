package cn.xinji.agentplatform.adjudication.adapter.out.dm8;

import cn.xinji.agentplatform.adjudication.application.PublicAdjudicationReferenceCatalogPort.Reference;
import java.util.List;
import java.util.Set;

final class PublicAdjudicationReferencePolicy {
    private static final Set<String> CATEGORIES = Set.of(
            "PUBLIC_LEGAL_REFERENCE", "PUBLIC_JUDGMENT_REFERENCE", "AUTHORIZED_TEMPLATE_REFERENCE");

    private PublicAdjudicationReferencePolicy() {}

    static void validateSource(PublicAdjudicationSourceApprovalRegistry approvals,
                               PublicAdjudicationReferenceImporter.SourceRegistration value) {
        if (!"APPROVED".equals(value.reviewStatus()) || value.externalModelAllowed() || !approvals.approves(value)
                || blank(value.sourceId()) || blank(value.name()) || blank(value.url()) || blank(value.accessMode())
                || blank(value.restrictions()) || blank(value.ownerRef()) || blank(value.allowedUses())
                || blank(value.allowedCategories()) || blank(value.forbiddenUses()) || value.reviewedAt() == null
                || blank(value.approvalId()) || !sha(value.approvalSha256()) || blank(value.retentionPeriod())
                || blank(value.attribution())) throw new IllegalStateException("SOURCE_NOT_ALLOWED");
    }

    static void validateRecord(PublicAdjudicationReferenceImporter.SourceRegistration source, Reference value) {
        boolean categoryAllowed = List.of(source.allowedCategories().split(",")).contains(value.category());
        boolean accessMatches = !"AUTHORIZED_TEMPLATE_REFERENCE".equals(value.category())
                || "AUTHORIZED_LOCAL_FILE".equals(source.accessMode());
        if (!CATEGORIES.contains(value.category()) || !categoryAllowed || !accessMatches
                || !source.sourceId().equals(value.source().sourceSystem()) || value.sourceTrace() == null
                || blank(value.referenceId()) || blank(value.source().sourceId()) || blank(value.source().version())
                || blank(value.source().location()) || blank(value.title()) || blank(value.sourceUrl()) || blank(value.capturedAt())
                || !"AVAILABLE".equals(value.availability()) || !"PUBLIC_PILOT".equals(value.dataMode())
                || !"NOT_CENTER_SOURCE".equals(value.authorityMarker()) || !"NOT_PROFESSIONAL_EVIDENCE".equals(value.professionalUse())
                || value.externalModelAllowed() || !"APPROVED_FOR_INTERNAL_DEVELOPMENT".equals(value.importDecision())
                || !source.approvalId().equals(value.approvalId()) || !source.approvalSha256().equals(value.approvalSha256())
                || !source.retentionPeriod().equals(value.retentionPeriod()) || !source.attribution().equals(value.attribution())
                || !sha(value.sourcePayloadSha256()) || blank(value.sourceTrace().sourceDatasetId())
                || !sha(value.sourceTrace().sourceRecordsSha256()) || value.sourceTrace().sourceSnapshotRefs().isEmpty()
                || !sha(value.sourceTrace().sourceRecordSha256()) || value.sourceTrace().sourceSnapshots().isEmpty()
                || value.sourceTrace().sourceSnapshots().stream().anyMatch(trace -> blank(trace.ref())
                    || !sha(trace.rawResponseSha256()) || blank(trace.sourceFile())
                    || !value.sourceTrace().sourceSnapshotRefs().contains(trace.ref()))) {
            throw new IllegalStateException("SOURCE_INTEGRITY_FAILURE");
        }
    }

    static boolean blank(String value) { return value == null || value.isBlank(); }
    static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
