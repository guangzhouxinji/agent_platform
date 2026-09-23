package cn.xinji.agentplatform.consultation.patentassistant.application;

import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.util.List;

public interface PatentAssistantSourcePort {
    SearchPage search(SearchRequest request);

    SourceDetail detail(String publicationNumber);

    SourceStatus status();

    record SearchRequest(String question, int page, int pageSize, String country) {}

    record SearchPage(List<String> searchTerms, List<PatentAssistantSource> sources,
                      int page, int pageSize, Long totalHits, boolean hasNext) {
        public SearchPage {
            searchTerms = List.copyOf(searchTerms);
            sources = List.copyOf(sources);
        }
    }

    record SourceDetail(String publicationNumber, String abstractState, String excerpt) {}

    record SourceStatus(String provider, String dataMode, String dataVersion, Integer sourceCount,
                        boolean externalModelUseAllowed, String authorityStatus, String usageDecision) {}
}
