package cn.xinji.agentplatform.consultation.patentlibrary.domain;

import java.time.LocalDate;
import java.util.List;

public record PatentLibraryRecord(
        String sourceId,
        String sourceRecordId,
        String sourceVersion,
        String applicationNumber,
        String publicationNumber,
        String grantPublicationNumber,
        String title,
        String patentType,
        List<String> applicants,
        List<String> inventors,
        String applicantAddress,
        LocalDate applicationDate,
        LocalDate publicationDate,
        LocalDate grantDate,
        List<PriorityItem> priorities,
        List<String> ipcClassifications,
        List<String> cpcClassifications,
        String designClassification,
        String sourceLegalStatus,
        String mappedLegalStatus,
        LocalDate statusDate,
        String sourceUpdatedAt,
        String sourceRef,
        String dataMode,
        List<DocumentVersion> versions) {

    public PatentLibraryRecord {
        applicants = List.copyOf(applicants);
        inventors = List.copyOf(inventors);
        priorities = List.copyOf(priorities);
        ipcClassifications = List.copyOf(ipcClassifications);
        cpcClassifications = List.copyOf(cpcClassifications);
        versions = List.copyOf(versions);
    }

    public record PriorityItem(String applicationNumber, LocalDate priorityDate, String country) {}

    public record DocumentVersion(
            String versionId,
            String documentNumber,
            LocalDate documentDate,
            String kind,
            ContentSection abstractSection,
            ContentSection claimsSection,
            ContentSection descriptionSection,
            ContentSection figuresSection,
            String sourceRef) {}

    public record ContentSection(String state, List<LocatedText> texts, String originalRef, String message) {
        public ContentSection { texts = List.copyOf(texts); }
    }

    public record LocatedText(String sourceLabel, String systemLocator, String text) {}

    public record EventResult(String state, List<PatentEvent> records, String message) {
        public EventResult { records = List.copyOf(records); }
    }

    public record PatentEvent(String eventId, String type, LocalDate businessDate, String result,
                              String documentVersionId, String authority, String sourceRef) {}
}
