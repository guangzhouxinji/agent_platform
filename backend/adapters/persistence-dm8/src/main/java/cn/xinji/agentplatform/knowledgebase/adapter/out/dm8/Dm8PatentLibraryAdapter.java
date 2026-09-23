package cn.xinji.agentplatform.knowledgebase.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.ContentSection;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.LocatedText;
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
public final class Dm8PatentLibraryAdapter implements PatentLibraryPort {
    private final List<PatentLibraryRecord> records;
    public Dm8PatentLibraryAdapter(Dm8KnowledgeBaseJsonReader reader) { records = reader.records("PATENT").stream().map(Dm8PatentLibraryAdapter::map).toList(); }
    @Override public List<PatentLibraryRecord> records() { return records; }
    @Override public Optional<PatentLibraryRecord> find(String sourceId) { return records.stream().filter(v -> v.sourceRecordId().equals(sourceId)).findFirst(); }
    @Override public String sourceVersion() { return "public-local-dm8-v1"; }
    @Override public String dataMode() { return "PUBLIC_LOCAL"; }
    @Override public PatentLibraryRecord.EventResult events(String sourceId) {
        if (find(sourceId).isEmpty()) throw new IllegalArgumentException("patent not found");
        return new PatentLibraryRecord.EventResult("SOURCE_NOT_PROVIDED", List.of(), "当前公开快照未采集法律事件");
    }

    private static PatentLibraryRecord map(JsonNode item) {
        String sourceRef = text(item, "sourceUrl");
        ContentSection abstractSection = section(item, "abstract", "abstractState", "ABS-001", sourceRef);
        ContentSection claimsSection = section(item, "claims", "claimsState", "CLM-001", sourceRef);
        DocumentVersion document = new DocumentVersion(text(item, "sourceVersion"), text(item, "publicationNumber"), date(item, "publicationDate"),
                "本地公开数据快照", abstractSection, claimsSection, missing(sourceRef, "说明书未采集"), missing(sourceRef, "附图未采集"), sourceRef);
        return new PatentLibraryRecord(text(item, "sourceId"), text(item, "sourceRecordId"), text(item, "sourceVersion"), nullable(item, "applicationNumber"),
                text(item, "publicationNumber"), null, text(item, "title"), "未采集", strings(item, "applicants"), strings(item, "inventors"), null,
                date(item, "applicationDate"), date(item, "publicationDate"), null, List.of(), strings(item, "ipc"), strings(item, "cpc"), null,
                null, "来源未提供", null, nullable(item, "capturedAt"), sourceRef, "PUBLIC_LOCAL", List.of(document));
    }
    private static ContentSection section(JsonNode item, String field, String state, String locator, String ref) {
        String value = nullable(item, field); return value == null ? missing(ref, field + " 未采集")
                : new ContentSection(nullable(item, state) == null ? "PARTIAL" : text(item, state), List.of(new LocatedText("公开来源节选", locator, value)), ref, "公开来源节选；不表示完整原文");
    }
    private static ContentSection missing(String ref, String message) { return new ContentSection("SOURCE_NOT_PROVIDED", List.of(), ref, message); }
    private static List<String> strings(JsonNode n, String f) { return StreamSupport.stream(n.path(f).spliterator(), false).map(JsonNode::asText).toList(); }
    private static String text(JsonNode n, String f) { String value=nullable(n,f); if(value==null)throw new IllegalArgumentException(f+" is required"); return value; }
    private static String nullable(JsonNode n, String f) { JsonNode v=n.get(f); return v==null||v.isNull()||v.asText().isBlank()?null:v.asText(); }
    private static LocalDate date(JsonNode n, String f) { String v=nullable(n,f); return v==null?null:LocalDate.parse(v); }
}
