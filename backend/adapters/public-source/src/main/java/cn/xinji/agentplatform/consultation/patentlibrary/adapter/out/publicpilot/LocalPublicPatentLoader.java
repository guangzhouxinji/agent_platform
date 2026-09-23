package cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.ContentSection;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.LocatedText;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class LocalPublicPatentLoader {
    private static final String RESOURCE = "/public-pilot/public-patents-local-v1.json";
    private final List<PatentLibraryRecord> records;

    public LocalPublicPatentLoader(ObjectMapper mapper) {
        try (InputStream input = LocalPublicPatentLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing local public patent resource: " + RESOURCE);
            records = mapper.readValue(input, new TypeReference<List<Item>>() {}).stream().map(LocalPublicPatentLoader::map).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load local public patents", exception);
        }
    }

    public List<PatentLibraryRecord> records() { return records; }

    private static PatentLibraryRecord map(Item item) {
        ContentSection abstractSection = section(item.abstractState(), "公开来源摘要", "ABS-001", item.abstractText(), item.sourceUrl(), "摘要");
        ContentSection claimsSection = section(item.claimsState(), "公开来源权利要求节选", "CLM-001", item.claims(), item.sourceUrl(), "权利要求");
        DocumentVersion document = new DocumentVersion(item.sourceVersion(), item.publicationNumber(), date(item.publicationDate()),
                "本地公开数据快照", abstractSection, claimsSection, missing(item.sourceUrl(), "说明书未采集"),
                missing(item.sourceUrl(), "附图未采集"), item.sourceUrl());
        return new PatentLibraryRecord(item.sourceId(), item.sourceRecordId(), item.sourceVersion(), item.applicationNumber(),
                item.publicationNumber(), null, item.title(), "未采集", item.applicants(), item.inventors(), null,
                date(item.applicationDate()), date(item.publicationDate()), null, List.of(), item.ipc(), item.cpc(), null,
                null, "来源未提供", null, item.capturedAt(), item.sourceUrl(), "PUBLIC_LOCAL", List.of(document));
    }

    private static ContentSection section(String state, String label, String locator, String text, String sourceRef, String name) {
        if (text == null || text.isBlank()) return missing(sourceRef, name + "未采集");
        return new ContentSection(state == null ? "PARTIAL" : state,
                List.of(new LocatedText(label, locator, text)), sourceRef, "公开来源节选；不表示完整原文");
    }

    private static ContentSection missing(String sourceRef, String message) {
        return new ContentSection("SOURCE_NOT_PROVIDED", List.of(), sourceRef, message);
    }

    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(String sourceId, String sourceRecordId, String sourceVersion, String applicationNumber,
                        String publicationNumber, String title, List<String> applicants, List<String> inventors,
                        String applicationDate, String publicationDate, List<String> ipc, List<String> cpc,
                        String sourceUrl, @JsonProperty("abstract") String abstractText, String abstractState,
                        String claims, String claimsState, String capturedAt) {}
}
