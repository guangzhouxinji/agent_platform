package cn.xinji.agentplatform.consultation.trademarkcomparison.domain;

import java.time.Instant;
import java.util.List;

public record TrademarkComparisonTask(String taskId, String requester, String idempotencyKey, String requestFingerprint,
        TrademarkComparisonCommand inputSnapshot, String status, String dataMode, String sourceVersion, String ruleVersion,
        String reportCapability, String reviewStatus, List<PairResult> pairs, List<String> limitations, String traceId, Instant createdAt) {
    public TrademarkComparisonTask { pairs = List.copyOf(pairs); limitations = List.copyOf(limitations); }
    public record PairResult(TrademarkComparisonCommand.DocumentRef candidate, String status, List<DimensionResult> dimensions,
            List<String> missingItems, String auxiliaryOpinion) {
        public PairResult { dimensions = List.copyOf(dimensions); missingItems = List.copyOf(missingItems); }
    }
    public record DimensionResult(TrademarkComparisonCommand.Dimension dimension, String state, String observation,
            List<String> evidence, String reason) {
        public DimensionResult { evidence = List.copyOf(evidence); }
    }
}
