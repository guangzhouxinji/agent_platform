package cn.xinji.agentplatform.consultation.unified;

import static org.assertj.core.api.Assertions.assertThat;

import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCandidate;
import cn.xinji.agentplatform.consultation.casesearch.domain.CaseSearchCandidate.EvidenceExcerpt;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCandidate;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.TrademarkSearchCommand;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailyConsultationEvidenceFactoryTest {
    @Test void buildsTrademarkEvidenceFromReturnedTaskSnapshot() {
        var candidate = new TrademarkSearchCandidate("TM-1", "123456", "清源", List.of("3"),
                List.of("化妆品", "洗面奶"), "有效", "CN", "PUBLIC_LOCAL", "v3", "2026-09-01", "值得比对",
                List.of(new TrademarkSearchCandidate.DimensionResult(TrademarkSearchCommand.Dimension.TEXT,
                        TrademarkSearchCandidate.State.EXECUTED, "文字近形")));

        var evidence = DailyConsultationEvidenceFactory.trademarks(List.of(candidate)).getFirst();

        assertThat(evidence.sourceId()).isEqualTo("TM-1");
        assertThat(evidence.evidenceText()).contains("注册号：123456", "类别：3", "商品/服务：化妆品、洗面奶",
                "TEXT[EXECUTED]：文字近形");
    }

    @Test void buildsCaseEvidenceWithOutcomeAndLocatedSourceText() {
        var candidate = new CaseSearchCandidate("CASE-1", "v2", "（2026）测试1号", "DOCKET-1", "商标侵权纠纷",
                "民事", "商标", "某知识产权法院", "一审", LocalDate.of(2026, 8, 1), "判决驳回部分请求",
                70, "较高词项相关", List.of("商标"), List.of("争议焦点命中"),
                new EvidenceExcerpt("被诉标识与注册商标主要识别部分不同", "裁判理由第12段", "P12", "SOURCE_TEXT"), "ref");

        var evidence = DailyConsultationEvidenceFactory.cases(List.of(candidate)).getFirst();

        assertThat(evidence.evidenceText()).contains("案号：（2026）测试1号", "结果节选：判决驳回部分请求",
                "原文证据：被诉标识与注册商标主要识别部分不同", "裁判理由第12段 / P12");
    }
}
