package cn.xinji.agentplatform.consultation.patentcomparison.domain;

import java.time.Instant;
import java.util.List;

public record PatentComparisonTask(String taskId,String requester,String idempotencyKey,String requestFingerprint,
        PatentComparisonCommand inputSnapshot,String status,String dataMode,String sourceVersion,String ruleVersion,
        String scoreCapability,String reviewStatus,List<PairResult> pairs,List<String> limitations,String traceId,Instant createdAt) {
    public PatentComparisonTask { pairs=List.copyOf(pairs);limitations=List.copyOf(limitations); }
    public record PairResult(PatentComparisonCommand.DocumentRef candidate,String status,List<FeatureMapping> mappings,
            List<String> similarities,List<String> differences,List<String> unexecutedItems,String auxiliaryOpinion) {
        public PairResult { mappings=List.copyOf(mappings);similarities=List.copyOf(similarities);differences=List.copyOf(differences);unexecutedItems=List.copyOf(unexecutedItems); }
    }
    public record FeatureMapping(String featureId,String targetText,String relation,String candidateEvidence,
            String candidateLocation,String difference,String reason) { }
}
