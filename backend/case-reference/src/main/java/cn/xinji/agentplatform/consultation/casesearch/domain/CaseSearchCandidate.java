package cn.xinji.agentplatform.consultation.casesearch.domain;

import java.time.LocalDate;
import java.util.List;

public record CaseSearchCandidate(
        String sourceId,
        String sourceVersion,
        String caseNumber,
        String docketNumber,
        String title,
        String caseType,
        String intellectualPropertyType,
        String institution,
        String procedure,
        LocalDate decisionDate,
        String outcomeExcerpt,
        int relevanceScore,
        String relevanceLabel,
        List<String> matchedKeywords,
        List<String> rankingBasis,
        EvidenceExcerpt evidence,
        String sourceRef) {

    public CaseSearchCandidate {
        matchedKeywords = List.copyOf(matchedKeywords);
        rankingBasis = List.copyOf(rankingBasis);
    }

    public record EvidenceExcerpt(String text, String sourceLocation, String systemLocator, String state) {}
}
