package cn.xinji.agentplatform.consultation.patentsearch.domain;

import java.util.List;

public record PatentSearchCandidate(
        String sourceId,
        String applicationNumber,
        String publicationNumber,
        String title,
        String patentType,
        String version,
        int score,
        String relevanceLabel,
        List<String> matchedFeatures,
        String matchingExcerpt,
        String location) {
    public PatentSearchCandidate {
        matchedFeatures = List.copyOf(matchedFeatures);
    }
}
