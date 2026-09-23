package cn.xinji.agentplatform.enforcement.application;

import cn.xinji.agentplatform.enforcement.domain.SourceLocator;
import java.util.List;

/** Read-only public reference catalog. It never supplies clues, evidence, rules, or work inputs. */
public interface PublicEnforcementReferenceCatalogPort {
    default List<Reference> list() { return List.of(); }
    default String dataMode() { return "LOCAL_SOURCE_UNAVAILABLE"; }
    default String catalogVersion() { return "unconfigured"; }
    default String limitation() { return "执法公开参考目录未配置。"; }

    record Reference(
            String referenceId,
            String category,
            SourceLocator source,
            String title,
            String publicationDate,
            String sourceStatusLabel,
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
            String attribution,
            String businessCategory,
            Integer sourcePageStart,
            Integer sourcePageEnd,
            String historicalCaseSummary,
            String titleExtractionNote,
            boolean businessWorkflowInputAllowed) {
        public Reference { missingFields = List.copyOf(missingFields); }

        public Reference(String referenceId, String category, SourceLocator source, String title,
                         String publicationDate, String sourceStatusLabel, List<String> missingFields,
                         String availability, String usageLimitation, String sourceUrl, String capturedAt,
                         String dataMode, String authorityMarker, String professionalUse,
                         boolean externalModelAllowed, String importDecision, SourceTrace sourceTrace,
                         String sourcePayloadSha256, String approvalId, String approvalSha256,
                         String retentionPeriod, String attribution) {
            this(referenceId, category, source, title, publicationDate, sourceStatusLabel, missingFields,
                    availability, usageLimitation, sourceUrl, capturedAt, dataMode, authorityMarker,
                    professionalUse, externalModelAllowed, importDecision, sourceTrace, sourcePayloadSha256,
                    approvalId, approvalSha256, retentionPeriod, attribution, null, null, null, null, null, false);
        }

        public Reference(String referenceId, String category, SourceLocator source, String title,
                         String publicationDate, String sourceStatusLabel, List<String> missingFields,
                         String availability, String usageLimitation) {
            this(referenceId, category, source, title, publicationDate, sourceStatusLabel, missingFields,
                    availability, usageLimitation, null, null, null, null, null, false, null, null,
                    null, null, null, null, null, null, null, null, null, null, false);
        }
    }

    record SourceTrace(String sourceDatasetId, String sourceRecordsSha256, String sourceRecordSha256,
                       List<String> sourceSnapshotRefs, List<SourceSnapshotTrace> sourceSnapshots) {
        public SourceTrace {
            sourceSnapshotRefs = List.copyOf(sourceSnapshotRefs);
            sourceSnapshots = List.copyOf(sourceSnapshots);
        }
    }

    record SourceSnapshotTrace(String ref, String rawResponseSha256, String sourceFile) {}
}
