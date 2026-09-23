package cn.xinji.agentplatform.consultation.trademarkcomparison.application;

import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkDocument;
import java.util.List;
import java.util.Optional;

public interface TrademarkComparisonSourcePort {
    Optional<TrademarkDocument> findTrademark(String sourceId);
    String comparisonSourceVersion();
    default String dataMode() { return "SYNTHETIC_V0"; }
    default String ruleVersion() { return "SYNTHETIC_TRADEMARK_OBSERVATION_V1"; }
    default List<String> limitations() {
        return List.of("仅比较合成商标名称和目录内受控读音，不代表法律近似判断", "含义、图形、整体视觉和商品服务关系规则尚未配置", "不提供百分数、注册成功率或审查结论，报告未经专业人员复核");
    }
}
