package cn.xinji.agentplatform.consultation.patentcomparison.domain;

import java.util.List;

public record PatentComparisonCommand(Purpose purpose, DocumentRef target, List<DocumentRef> candidates, List<Feature> confirmedFeatures) {
    public PatentComparisonCommand { candidates=List.copyOf(candidates==null?List.of():candidates); confirmedFeatures=List.copyOf(confirmedFeatures==null?List.of():confirmedFeatures); }
    public enum Purpose { TECHNICAL_SIMILARITY, CLAIM_COMPARISON, NOVELTY_ASSISTANCE, DESIGN_ASSISTANCE }
    public record DocumentRef(String sourceId,String version) { }
    public record Feature(String featureId,String text,String sourceLocation) { }
}
