package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;

public record EvidenceReference(
        String evidenceRefId,
        int version,
        SourceLocator source,
        String controlledResourceRef,
        String relatedClueId,
        String collector,
        String collectionMissingReason,
        String occurrenceTime,
        String collectionTime,
        String uploadTime,
        String availability,
        String originalRef,
        String derivedKind,
        Instant recordedAt,
        String scopeLabel) {

    public EvidenceReference {
        if (controlledResourceRef != null && controlledResourceRef.matches("^[A-Za-z]:[\\\\/].*")) {
            throw new IllegalArgumentException("Physical paths are not controlled resource references");
        }
        if ((collector == null || collector.isBlank()) && (collectionMissingReason == null || collectionMissingReason.isBlank())) {
            throw new IllegalArgumentException("Missing collection information requires a reason");
        }
    }
}
