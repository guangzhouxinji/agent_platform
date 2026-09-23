package cn.xinji.agentplatform.consultation.adapter.out.mockcenter;

import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.application.PatentCatalogPort;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCandidate;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.util.List;
import java.util.UUID;

/** Development adapter: model extraction sees user input only; catalog results never leave this process. */
public final class MockCenterConsultationPatentSearchAdapter implements PatentSearchPort {
    private static final String PURPOSE = "TECHNICAL_REFERENCE";
    private final MockCenterPatentCatalogAdapter searchCatalog;
    private final PatentAnalysisPort analysis;

    public MockCenterConsultationPatentSearchAdapter(MockCenterPatentCatalogAdapter searchCatalog, PatentAnalysisPort analysis) {
        this.searchCatalog = searchCatalog;
        this.analysis = analysis;
    }

    @Override
    public SearchResult search(String confirmedInput) {
        if (!analysis.status().configured()) {
            return failure("DeepSeek 未配置，未执行模拟中心检索。");
        }

        List<String> features;
        try {
            features = analysis.extractFeatures(confirmedInput, PURPOSE, UUID.randomUUID().toString()).features();
        } catch (RuntimeException exception) {
            return failure("DeepSeek 特征提取失败，未执行模拟中心检索。");
        }

        try {
            PatentSearchCommand command = new PatentSearchCommand(
                    PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                    confirmedInput,
                    PURPOSE,
                    features,
                    new PatentSearchCommand.SearchScope(List.of(), List.of("CN"), List.of(), null, null, null, null));
            PatentCatalogPort.CatalogResult result = searchCatalog.search(command);
            if (result.candidates().isEmpty()) {
                return new SearchResult(SearchResult.Type.NO_RESULT, List.of(),
                        combine(result.limitation(), "当前公开试验目录未返回匹配候选，不等于不存在相关技术。"),
                        result.actualScope());
            }
            List<ConsultationCandidate> candidates = result.candidates().stream().map(this::map).toList();
            return new SearchResult(SearchResult.Type.SUCCESS, candidates, result.limitation(), result.actualScope());
        } catch (RuntimeException exception) {
            return failure("模拟中心目录当前不可用，未形成候选。");
        }
    }

    @Override
    public boolean isSourceAvailable(String sourceId, String sourceVersion) {
        try {
            return searchCatalog.isSourceAvailable(sourceId, sourceVersion);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private ConsultationCandidate map(PatentSearchCandidate candidate) {
        String basis = "标题：" + candidate.title()
                + value("；公开（公告）号：", candidate.publicationNumber())
                + (candidate.matchedFeatures().isEmpty() ? "" : "；命中特征：" + String.join("、", candidate.matchedFeatures()))
                + value("；摘要片段：", candidate.matchingExcerpt());
        return new ConsultationCandidate(candidate.sourceId(), candidate.version(), candidate.location(), basis,
                "标题：" + candidate.title()
                        + "\n公开（公告）号：" + candidate.publicationNumber()
                        + "\n专利类型：" + candidate.patentType()
                        + "\n匹配片段：" + candidate.matchingExcerpt()
                        + "\n片段定位：" + candidate.location());
    }

    private static SearchResult failure(String limitation) {
        return new SearchResult(SearchResult.Type.TOOL_FAILURE, List.of(), limitation, "PUBLIC_PILOT 模拟中心目录");
    }

    private static String combine(String first, String second) {
        return first == null || first.isBlank() ? second : first + " " + second;
    }

    private static String value(String prefix, String value) {
        return value == null || value.isBlank() ? "" : prefix + value;
    }
}
