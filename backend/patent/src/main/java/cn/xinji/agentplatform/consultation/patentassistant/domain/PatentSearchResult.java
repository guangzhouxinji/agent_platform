package cn.xinji.agentplatform.consultation.patentassistant.domain;

import java.util.List;

public record PatentSearchResult(
        String question,
        List<String> searchTerms,
        List<PatentAssistantSource> sources,
        String provider,
        String dataMode,
        String dataVersion,
        boolean externalModelUseAllowed,
        String traceId,
        int page,
        int pageSize,
        Long totalHits,
        boolean hasNext,
        String appliedCountry,
        String limitation) {
    public PatentSearchResult {
        searchTerms = List.copyOf(searchTerms);
        sources = List.copyOf(sources);
    }
}
