package cn.xinji.agentplatform.consultation.trademarksearch.adapter.in.web;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import java.util.List;

public record CreateTrademarkSearchTaskRequest(
        String inputMode,
        String name,
        List<String> categories,
        List<String> goodsServices,
        List<String> dimensions,
        ScopeRequest scope,
        Integer page,
        Integer pageSize) {

    TrademarkSearchCommand toCommand() {
        try {
            return new TrademarkSearchCommand(
                    TrademarkSearchCommand.InputMode.valueOf(inputMode),
                    name,
                    null,
                    categories,
                    goodsServices,
                    dimensions == null ? List.of() : dimensions.stream()
                            .map(TrademarkSearchCommand.Dimension::valueOf).toList(),
                    scope == null ? TrademarkSearchCommand.SearchScope.empty() : scope.toScope(),
                    page == null ? 1 : page,
                    pageSize == null ? 20 : pageSize);
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid trademark search request", exception);
        }
    }

    public record ScopeRequest(List<String> statuses, List<String> regions, List<String> dataSources) {
        TrademarkSearchCommand.SearchScope toScope() {
            return new TrademarkSearchCommand.SearchScope(statuses, regions, dataSources);
        }
    }
}
