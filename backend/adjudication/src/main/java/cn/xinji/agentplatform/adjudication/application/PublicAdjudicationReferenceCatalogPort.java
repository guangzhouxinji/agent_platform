package cn.xinji.agentplatform.adjudication.application;

import java.util.List;

/** Read-only development catalog that never supplies cases, materials, legal candidates, or drafts. */
public interface PublicAdjudicationReferenceCatalogPort {
    default List<Reference> list() { return List.of(); }
    default String dataMode() { return "LOCAL_SOURCE_UNAVAILABLE"; }
    default String catalogVersion() { return "unconfigured"; }
    default String limitation() { return "裁决公开参考目录未配置。"; }

    record Reference(
            String referenceId,
            String category,
            SourceLocator source,
            String title,
            String publicationDate,
            String institution,
            String documentType,
            List<String> missingFields,
            String availability,
            String usageLimitation,
            String sourceUrl,
            String capturedAt,
            String dataMode,
            String authorityMarker,
            String professionalUse,
            boolean externalModelAllowed,
            String importDecision,
            SourceTrace sourceTrace,
            String sourcePayloadSha256,
            String approvalId,
            String approvalSha256,
            String retentionPeriod,
            String attribution) {
        public Reference { missingFields = List.copyOf(missingFields); }
    }

    record SourceLocator(String sourceSystem, String sourceId, String version, String location) {}

    record SourceTrace(String sourceDatasetId, String sourceRecordsSha256, String sourceRecordSha256,
                       List<String> sourceSnapshotRefs, List<SourceSnapshotTrace> sourceSnapshots) {
        public SourceTrace {
            sourceSnapshotRefs = List.copyOf(sourceSnapshotRefs);
            sourceSnapshots = List.copyOf(sourceSnapshots);
        }
    }

    record SourceSnapshotTrace(String ref, String rawResponseSha256, String sourceFile) {}
}
