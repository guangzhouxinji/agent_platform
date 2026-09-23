package cn.xinji.agentplatform.consultation.caselibrary.domain;

import java.time.LocalDate;
import java.util.List;

public record CaseLibraryRecord(
        String sourceId, String sourceRecordId, String sourceVersion, String caseNumber, String docketNumber,
        String title, String caseType, String intellectualPropertyType, String institution, String procedure,
        LocalDate hearingDate, LocalDate decisionDate, String outcomeExcerpt, String sourceUpdatedAt,
        String fetchedAtHint, String sourceRef, String dataMode, List<DocumentVersion> documents,
        List<Attachment> attachments, List<Relation> relations, List<AnnotationVersion> annotations) {
    public CaseLibraryRecord {
        documents = List.copyOf(documents); attachments = List.copyOf(attachments);
        relations = List.copyOf(relations); annotations = List.copyOf(annotations);
    }
    public record DocumentVersion(String versionId, String state, String ocrState, List<LocatedParagraph> paragraphs,
                                  String originalRef, String message) {
        public DocumentVersion { paragraphs = List.copyOf(paragraphs); }
    }
    public record LocatedParagraph(String structure, String sourceLocation, String systemLocator, String text) {}
    public record Attachment(String attachmentId, String name, String state, String sourceRef, String message) {}
    public record Relation(String relationType, String relatedSourceRecordId, String relatedProcedure, String basis) {}
    public record AnnotationVersion(String annotationId, int version, String state, String sourceVersion,
                                    List<LocatedClaim> facts, List<LocatedClaim> issues, List<LocatedClaim> holdings,
                                    List<LawReference> lawReferences, String reviewedBy, String reviewedAt) {
        public AnnotationVersion { facts=List.copyOf(facts); issues=List.copyOf(issues); holdings=List.copyOf(holdings); lawReferences=List.copyOf(lawReferences); }
    }
    public record LocatedClaim(String text, String verificationState, String sourceLocation) {}
    public record LawReference(String regulationId, String title, String article, String versionState, String sourceLocation, String originalCitation) {}
    public record AnnotationResult(String state, AnnotationVersion annotation, String message) {}
}
