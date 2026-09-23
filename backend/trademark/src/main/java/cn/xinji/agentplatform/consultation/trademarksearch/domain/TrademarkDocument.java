package cn.xinji.agentplatform.consultation.trademarksearch.domain;

import java.util.List;

public record TrademarkDocument(String sourceId, String registrationNumber, String name, String phonetic,
                                List<String> categories, List<String> goodsServices, String status, String region,
                                String dataSource, String version, String updatedAt) {
    public TrademarkDocument { categories = List.copyOf(categories); goodsServices = List.copyOf(goodsServices); }
}
