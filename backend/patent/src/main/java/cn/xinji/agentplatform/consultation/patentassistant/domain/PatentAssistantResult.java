package cn.xinji.agentplatform.consultation.patentassistant.domain;

import java.util.List;

public record PatentAssistantResult(
        String question,
        List<String> searchTerms,
        String analysis,
        List<PatentAssistantSource> sources,
        String dataMode,
        String dataVersion,
        String model,
        String traceId,
        String limitation) {
    public PatentAssistantResult {
        searchTerms = List.copyOf(searchTerms);
        sources = List.copyOf(sources);
    }
}
