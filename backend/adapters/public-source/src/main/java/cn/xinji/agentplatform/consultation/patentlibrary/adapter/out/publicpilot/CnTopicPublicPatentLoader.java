package cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.ContentSection;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.DocumentVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CnTopicPublicPatentLoader {
    private static final String RESOURCE = "/public-pilot/public-patents-cn-topics-v1.json";
    private final List<PatentLibraryRecord> records;

    public CnTopicPublicPatentLoader(ObjectMapper mapper) {
        try (InputStream input = CnTopicPublicPatentLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing public pilot patent resource: " + RESOURCE);
            records = mapper.readValue(input, new TypeReference<List<Item>>() {}).stream().map(CnTopicPublicPatentLoader::map).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load CN topic public pilot patents", exception);
        }
    }

    public List<PatentLibraryRecord> records() { return records; }

    private static PatentLibraryRecord map(Item item) {
        String versionId = "cn-topic-public-pilot-v1";
        ContentSection missingAbstract = missing(item.sourceUrl() + "#abstract", "摘要未采集");
        ContentSection missingClaims = missing(item.sourceUrl() + "#claims", "权利要求未采集");
        ContentSection missingDescription = missing(item.sourceUrl() + "#description", "说明书未采集");
        ContentSection missingFigures = missing(item.sourceUrl() + "#figures", "附图未采集");
        DocumentVersion version = new DocumentVersion(versionId, item.publicationNumber(), date(item.publicationDate()),
                "公开检索著录记录", missingAbstract, missingClaims, missingDescription, missingFigures, item.sourceUrl());
        return new PatentLibraryRecord(item.sourceId(), item.sourceRecordId(), item.sourceVersion(), null,
                item.publicationNumber(), null, item.title(), "未采集", item.applicants(), List.of(), null,
                null, date(item.publicationDate()), null, List.of(), List.of(), List.of(), null,
                null, "未采集", null, null, item.sourceUrl(), "PUBLIC_PILOT", List.of(version));
    }

    private static ContentSection missing(String sourceRef, String message) {
        return new ContentSection("SOURCE_NOT_PROVIDED", List.of(), sourceRef, message);
    }

    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(String sourceId, String sourceRecordId, String sourceVersion, String publicationNumber,
                        String title, List<String> applicants, String publicationDate, String sourceUrl) {}
}
