package cn.xinji.agentplatform.consultation.caselibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.LocatedParagraph;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class PublicPilotCaseLoader {
    private static final String RESOURCE = "/public-pilot/public-cases-v1.json";
    private final List<CaseLibraryRecord> records;

    public PublicPilotCaseLoader(ObjectMapper mapper) {
        try (InputStream input = PublicPilotCaseLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing public pilot case resource: " + RESOURCE);
            records = mapper.readValue(input, new TypeReference<List<Item>>() {}).stream().map(PublicPilotCaseLoader::map).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load public pilot cases", exception);
        }
    }

    public List<CaseLibraryRecord> records() { return records; }

    private static CaseLibraryRecord map(Item item) {
        List<DocumentVersion> documents = item.documents().stream().map(document -> new DocumentVersion(
                document.versionId(), document.state(), document.ocrState(),
                document.text() == null || document.text().isBlank() ? List.of()
                        : List.of(new LocatedParagraph("裁判原文", document.sourceLocator(), "SOURCE-TEXT", document.text())),
                document.originalRef(), null)).toList();
        return new CaseLibraryRecord(item.sourceId(), item.sourceRecordId(), item.sourceVersion(), item.caseNumber(),
                item.docketNumber(), item.title(), item.caseType(), item.intellectualPropertyType(), item.institution(),
                item.procedure(), date(item.hearingDate()), date(item.decisionDate()), item.outcomeExcerpt(),
                item.sourceUpdatedAt(), item.capturedAt(), item.sourceRef(), "PUBLIC_PILOT", documents,
                List.of(), List.of(), List.of());
    }

    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(String sourceId, String sourceRecordId, String sourceVersion, String caseNumber,
                        String docketNumber, String title, String caseType, String intellectualPropertyType,
                        String institution, String procedure, String hearingDate, String decisionDate,
                        String outcomeExcerpt, String sourceUpdatedAt, String capturedAt, String sourceRef,
                        List<DocumentItem> documents) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DocumentItem(String versionId, String state, String ocrState, String text,
                                String originalRef, String sourceLocator) {}
}
