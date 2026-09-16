package cn.xinji.agentplatform.enforcement.domain;

import java.util.List;

public record EnforcementSnapshot(
        EnforcementWorkItem workItem,
        List<ClueAnalysisVersion> clueAnalyses,
        List<EvidenceReference> evidenceReferences,
        List<EvidenceRelationSetVersion> relationSets,
        List<AuxiliaryAnalysisVersion> auxiliaryAnalyses,
        List<HumanReviewRecord> reviews,
        List<String> reviewImpacts) {

    public EnforcementSnapshot {
        clueAnalyses = List.copyOf(clueAnalyses);
        evidenceReferences = List.copyOf(evidenceReferences);
        relationSets = List.copyOf(relationSets);
        auxiliaryAnalyses = List.copyOf(auxiliaryAnalyses);
        reviews = List.copyOf(reviews);
        reviewImpacts = List.copyOf(reviewImpacts);
    }

    public static EnforcementSnapshot open(EnforcementWorkItem workItem) {
        return new EnforcementSnapshot(workItem, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
