package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;
import java.util.List;

public record EvidenceRelationSetVersion(
        String relationSetId,
        int version,
        String clueAnalysisRef,
        SourceLocator ruleRef,
        List<String> evidenceVersionRefs,
        List<Relation> relations,
        List<TimelineEntry> timeline,
        List<String> gaps,
        List<String> conflicts,
        List<String> unprocessedItems,
        String completeness,
        List<String> needsReviewReasons,
        Instant createdAt) {

    public EvidenceRelationSetVersion {
        evidenceVersionRefs = List.copyOf(evidenceVersionRefs);
        relations = List.copyOf(relations);
        timeline = List.copyOf(timeline);
        gaps = List.copyOf(gaps);
        conflicts = List.copyOf(conflicts);
        unprocessedItems = List.copyOf(unprocessedItems);
        needsReviewReasons = List.copyOf(needsReviewReasons);
        if ("COMPLETE_VALID_CHAIN".equals(completeness)) throw new IllegalArgumentException("V0 cannot confirm a complete valid evidence chain");
    }

    public record Relation(String issue, String evidenceVersionRef, String type, String reason, SourceLocator source) {}
    public record TimelineEntry(String kind, String originalValue, String normalizedValue, SourceLocator source) {}
}
