package cn.xinji.agentplatform.consultation.trademarksearch.domain;

import java.util.List;

public record TrademarkSearchCandidate(String sourceId, String registrationNumber, String name, List<String> categories,
                                       List<String> goodsServices, String status, String region, String dataSource,
                                       String sourceVersion, String updatedAt, String relevanceLabel, List<DimensionResult> dimensions) {
    public TrademarkSearchCandidate { categories = List.copyOf(categories); goodsServices = List.copyOf(goodsServices); dimensions = List.copyOf(dimensions); }
    public record DimensionResult(TrademarkSearchCommand.Dimension dimension, State state, String basis) {}
    public enum State { EXECUTED, NOT_ANALYZED }
}
