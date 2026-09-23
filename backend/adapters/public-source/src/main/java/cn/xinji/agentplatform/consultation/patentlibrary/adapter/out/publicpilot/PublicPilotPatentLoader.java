package cn.xinji.agentplatform.consultation.patentlibrary.adapter.out.publicpilot;

import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.ContentSection;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.DocumentVersion;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord.LocatedText;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class PublicPilotPatentLoader {
    private static final String RESOURCE = "/public-pilot/public-patents-v0.1.json";
    private final List<PatentLibraryRecord> records;

    public PublicPilotPatentLoader(ObjectMapper mapper) {
        try (InputStream input = PublicPilotPatentLoader.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing public pilot patent resource: " + RESOURCE);
            records = mapper.readValue(input, new TypeReference<List<Item>>() {}).stream().map(PublicPilotPatentLoader::map).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load public pilot patent resource", exception);
        }
    }

    public List<PatentLibraryRecord> records() { return records; }

    private static PatentLibraryRecord map(Item item) {
        String versionId = "public-pilot-v1";
        ContentSection abstractSection = new ContentSection(item.abstractState(),
                List.of(new LocatedText("公开摘要机器摘要", "ABS-001", item.abstractText())), item.sourceUrl() + "#abstract",
                "公开网页采集内容；中文为机器摘要，不是专利原文或专业结论");
        ContentSection missingClaims = missing(item.sourceUrl() + "#claims", "权利要求未采集");
        ContentSection missingDescription = missing(item.sourceUrl() + "#description", "说明书未采集");
        ContentSection missingFigures = missing(item.sourceUrl() + "#figures", "附图未采集");
        DocumentVersion version = new DocumentVersion(versionId, item.publicationNumber(), date(item.publicationDate()),
                "公开网页开发测试样本", abstractSection, missingClaims, missingDescription, missingFigures, item.sourceUrl());
        return new PatentLibraryRecord("ESPACENET_PUBLIC_PILOT", item.sourceRecordId(), "public-pilot-v0.1",
                item.applicationNumber(), item.publicationNumber(), null, item.title(), "发明", item.applicants(), item.inventors(),
                null, date(item.applicationDate()), date(item.publicationDate()), null, List.of(), item.ipc(), List.of(), null,
                "NOT_COLLECTED", "未采集", null, "2026-09-16T00:00:00+08:00", item.sourceUrl(), "PUBLIC_PILOT", List.of(version));
    }

    private static ContentSection missing(String sourceRef, String message) {
        return new ContentSection("SOURCE_NOT_PROVIDED", List.of(), sourceRef, message);
    }

    private static LocalDate date(String value) { return value == null || value.isBlank() ? null : LocalDate.parse(value); }

    private record Item(String sourceRecordId, String publicationNumber, String applicationNumber, String title,
                        List<String> applicants, List<String> inventors, String applicationDate, String publicationDate,
                        List<String> ipc, String sourceUrl, @JsonProperty("abstract") String abstractText,
                        String abstractState, String topic) {}
}
