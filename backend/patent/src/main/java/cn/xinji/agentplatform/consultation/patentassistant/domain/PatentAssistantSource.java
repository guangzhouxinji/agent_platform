package cn.xinji.agentplatform.consultation.patentassistant.domain;

public record PatentAssistantSource(
        String citation,
        String sourceRecordId,
        String publicationNumber,
        String applicationNumber,
        String title,
        String applicant,
        String publicationDate,
        String sourceUrl,
        String abstractState,
        String matchBasis,
        String excerpt) {}
