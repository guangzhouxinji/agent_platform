package cn.xinji.agentplatform.consultation.patentcomparison.application;

import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentDocument;
import java.util.List;
import java.util.Optional;

public interface PatentComparisonSourcePort {
    Optional<PatentDocument> findPatent(String sourceId);
    String sourceVersion();
    default String dataMode() { return "SYNTHETIC_V0"; }
    default String ruleVersion() { return "SYNTHETIC_CLAIM_COMPARE_V1"; }
    default List<String> limitations() {
        return List.of("仅对合成发明/实用新型权利要求逐字对照", "未标定数值评分，不构成新颖性、侵权或保护范围结论", "报告未经专业人员复核");
    }
}
