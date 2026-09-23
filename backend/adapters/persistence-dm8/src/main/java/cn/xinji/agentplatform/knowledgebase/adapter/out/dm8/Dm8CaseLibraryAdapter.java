package cn.xinji.agentplatform.knowledgebase.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.caselibrary.application.CaseLibraryPort;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.caselibrary.domain.CaseLibraryRecord.LocatedParagraph;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test-synthetic")
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "dm8")
public final class Dm8CaseLibraryAdapter implements CaseLibraryPort {
    private final List<CaseLibraryRecord> records;
    public Dm8CaseLibraryAdapter(Dm8KnowledgeBaseJsonReader reader) { records = reader.records("CASE").stream().map(Dm8CaseLibraryAdapter::map).toList(); }
    @Override public List<CaseLibraryRecord> records() { return records; }
    @Override public Optional<CaseLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-dm8-v1"; }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
    private static CaseLibraryRecord map(JsonNode item) {
        List<DocumentVersion> documents=StreamSupport.stream(item.path("documents").spliterator(),false).map(v->{String body=nullable(v,"text");return new DocumentVersion(
                text(v,"versionId"),text(v,"state"),nullable(v,"ocrState"),body==null?List.of():List.of(new LocatedParagraph("裁判原文",nullable(v,"sourceLocator"),"SOURCE-TEXT",body)),
                nullable(v,"originalRef"),null);}).toList();
        return new CaseLibraryRecord(text(item,"sourceId"),text(item,"sourceRecordId"),text(item,"sourceVersion"),nullable(item,"caseNumber"),
                nullable(item,"docketNumber"),text(item,"title"),nullable(item,"caseType"),nullable(item,"intellectualPropertyType"),nullable(item,"institution"),
                nullable(item,"procedure"),date(item,"hearingDate"),date(item,"decisionDate"),nullable(item,"outcomeExcerpt"),nullable(item,"sourceUpdatedAt"),
                nullable(item,"capturedAt"),text(item,"sourceRef"),"PUBLIC_LOCAL",documents,List.of(),List.of(),List.of());
    }
    private static String text(JsonNode n,String f){String v=nullable(n,f);if(v==null)throw new IllegalArgumentException(f+" is required");return v;}
    private static String nullable(JsonNode n,String f){JsonNode v=n.get(f);return v==null||v.isNull()||v.asText().isBlank()?null:v.asText();}
    private static LocalDate date(JsonNode n,String f){String v=nullable(n,f);return v==null?null:LocalDate.parse(v);}
}
