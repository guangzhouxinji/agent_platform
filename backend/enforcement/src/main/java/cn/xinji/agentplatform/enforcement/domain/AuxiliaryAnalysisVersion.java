package cn.xinji.agentplatform.enforcement.domain;

import java.time.Instant;
import java.util.List;

public record AuxiliaryAnalysisVersion(
        String analysisResultId,
        int version,
        String clueAnalysisRef,
        String relationSetRef,
        List<String> evidenceVersionRefs,
        SourceLocator ruleRef,
        SourceLocator rightStatusRef,
        List<AnalysisItem> items,
        String auxiliaryOutcome,
        List<String> gaps,
        List<String> unprocessedItems,
        List<String> needsReviewReasons,
        String previousVersionRef,
        boolean notOfficialDecision,
        Instant createdAt,
        String scopeLabel) {

    public AuxiliaryAnalysisVersion {
        evidenceVersionRefs = List.copyOf(evidenceVersionRefs);
        items = List.copyOf(items);
        gaps = List.copyOf(gaps);
        unprocessedItems = List.copyOf(unprocessedItems);
        needsReviewReasons = List.copyOf(needsReviewReasons);
        if (!notOfficialDecision) throw new IllegalArgumentException("An enforcement V0 analysis is never an official decision");
        if (!List.of("SUSPECTED_SUPPORT", "EXCLUSION_BASIS", "MATERIALS_INSUFFICIENT").contains(auxiliaryOutcome)) {
            throw new IllegalArgumentException("Unsupported auxiliary outcome");
        }
    }

    public record AnalysisItem(String contentKind, String content, List<SourceLocator> sources, String uncertainty) {
        public AnalysisItem { sources = List.copyOf(sources); }
    }
}
