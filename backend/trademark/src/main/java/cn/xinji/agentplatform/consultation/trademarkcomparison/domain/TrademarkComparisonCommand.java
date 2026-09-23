package cn.xinji.agentplatform.consultation.trademarkcomparison.domain;

import java.util.List;

public record TrademarkComparisonCommand(Purpose purpose, DocumentRef target, List<DocumentRef> candidates,
        List<String> confirmedCategories, List<String> confirmedGoodsServices, List<Dimension> dimensions) {
    public TrademarkComparisonCommand {
        candidates = copy(candidates); confirmedCategories = copy(confirmedCategories);
        confirmedGoodsServices = copy(confirmedGoodsServices); dimensions = copy(dimensions);
    }
    private static <T> List<T> copy(List<T> values) { return values == null ? List.of() : List.copyOf(values); }
    public enum Purpose { AUXILIARY_COMPARISON }
    public enum Dimension { TEXT, PHONETIC, SEMANTIC, IMAGE, OVERALL_VISUAL, GOODS_SERVICES }
    public record DocumentRef(String sourceId, String version) { }
}
