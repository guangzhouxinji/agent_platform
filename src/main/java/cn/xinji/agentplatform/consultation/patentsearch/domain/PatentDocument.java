package cn.xinji.agentplatform.consultation.patentsearch.domain;

import java.time.LocalDate;
import java.util.List;

public record PatentDocument(
        String sourceId,
        String applicationNumber,
        String publicationNumber,
        String title,
        String patentType,
        String region,
        String classification,
        LocalDate applicationDate,
        LocalDate publicationDate,
        String version,
        String abstractText,
        String claimsText,
        List<String> featureTags) {
    public PatentDocument {
        featureTags = List.copyOf(featureTags);
    }
}
