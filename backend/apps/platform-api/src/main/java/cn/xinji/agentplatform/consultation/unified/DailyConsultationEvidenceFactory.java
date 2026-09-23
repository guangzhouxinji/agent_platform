package cn.xinji.agentplatform.consultation.unified;

import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCandidate;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import java.util.List;

final class DailyConsultationEvidenceFactory {
    private DailyConsultationEvidenceFactory() {}

    static List<ConsultationCandidate> trademarks(List<TrademarkSearchCandidate> candidates) {
        return candidates.stream().map(value -> new ConsultationCandidate(
                value.sourceId(), value.sourceVersion(), "商标目录记录",
                value.dimensions().stream().map(item -> item.dimension() + "：" + item.basis())
                        .reduce((left, right) -> left + "；" + right).orElse(""),
                "名称：" + text(value.name())
                        + "\n注册号：" + text(value.registrationNumber())
                        + "\n类别：" + String.join("、", value.categories())
                        + "\n商品/服务：" + String.join("、", value.goodsServices())
                        + "\n状态：" + text(value.status())
                        + "\n地域：" + text(value.region())
                        + "\n维度依据：" + value.dimensions().stream()
                                .map(item -> item.dimension() + "[" + item.state() + "]：" + text(item.basis()))
                                .reduce((left, right) -> left + "；" + right).orElse("未提供")))
                .toList();
    }

    static List<ConsultationCandidate> cases(List<CaseSearchCandidate> candidates) {
        return candidates.stream().map(value -> new ConsultationCandidate(
                value.sourceId(), value.sourceVersion(), value.evidence().sourceLocation(),
                String.join("；", value.rankingBasis()),
                "案例名称：" + text(value.title())
                        + "\n案号：" + text(value.caseNumber())
                        + "\n机构：" + text(value.institution())
                        + "\n程序：" + text(value.procedure())
                        + "\n裁判日期：" + (value.decisionDate() == null ? "未提供" : value.decisionDate())
                        + "\n结果节选：" + text(value.outcomeExcerpt())
                        + "\n原文证据：" + text(value.evidence().text())
                        + "\n原文定位：" + text(value.evidence().sourceLocation())
                        + " / " + text(value.evidence().systemLocator())))
                .toList();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "未提供" : value.trim();
    }
}
