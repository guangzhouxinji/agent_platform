package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.publiclocal.PublicLocalPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicLocalPatentCatalogAdapterTest {
    @Test
    void appliesRequestedTypeAndDateFiltersAndDisclosesActualScope() {
        var adapter = new PublicLocalPatentCatalogAdapter(library());
        var scope = new PatentSearchCommand.SearchScope(List.of("实用新型"), List.of("CN"), List.of("B05C"),
                null, null, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        var result = adapter.search(new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES,
                "水性粘结剂", "TECHNICAL_REFERENCE", List.of("水性粘结剂"), scope));

        assertEquals("PUBLIC_LOCAL_TEXT_MATCH_V1", adapter.descriptor().scoringVersion());
        assertEquals(List.of("PAT-UTILITY"), result.candidates().stream().map(value -> value.sourceId()).toList());
        assertTrue(result.actualScope().contains("类型=实用新型"));
        assertTrue(result.actualScope().contains("命中=1; 返回=1"));
        assertFalse(result.partial());
    }

    @Test
    void capsLargeResultSetsAndDisclosesTheTruncation() {
        List<PatentLibraryRecord> records = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            records.add(record("PAT-" + index, "发明", LocalDate.of(2024, 4, 2), "B05C9/00"));
        }
        var adapter = new PublicLocalPatentCatalogAdapter(library(records));

        var result = adapter.search(new PatentSearchCommand(PatentSearchCommand.InputMode.CORE_FEATURES,
                "水性粘结剂", "TECHNICAL_REFERENCE", List.of("水性粘结剂"), PatentSearchCommand.SearchScope.empty()));

        assertEquals(100, result.candidates().size());
        assertTrue(result.partial());
        assertTrue(result.actualScope().contains("命中=101; 返回=100"));
        assertTrue(result.limitation().contains("已截断返回"));
    }

    private static PatentLibraryPort library() {
        List<PatentLibraryRecord> records = List.of(
                record("PAT-UTILITY", "实用新型", LocalDate.of(2024, 4, 2), "B05C9/00"),
                record("PAT-INVENTION", "发明", LocalDate.of(2024, 4, 2), "B05C9/00"),
                record("PAT-OLD", "实用新型", LocalDate.of(2023, 4, 2), "B05C9/00"));
        return library(records);
    }

    private static PatentLibraryPort library(List<PatentLibraryRecord> records) {
        return new PatentLibraryPort() {
            @Override public List<PatentLibraryRecord> records() { return records; }
            @Override public Optional<PatentLibraryRecord> find(String sourceId) { return records.stream().filter(value -> value.sourceRecordId().equals(sourceId)).findFirst(); }
            @Override public PatentLibraryRecord.EventResult events(String sourceId) { return new PatentLibraryRecord.EventResult("SOURCE_NOT_PROVIDED", List.of(), "未采集"); }
            @Override public String sourceVersion() { return "public-local-test-v1"; }
            @Override public String dataMode() { return "PUBLIC_LOCAL"; }
        };
    }

    private static PatentLibraryRecord record(String id, String type, LocalDate publicationDate, String ipc) {
        var content = new PatentLibraryRecord.ContentSection("PARTIAL",
                List.of(new PatentLibraryRecord.LocatedText("公开来源节选", "摘要", "水性粘结剂涂布工艺")),
                "https://example.test/" + id, "公开来源节选");
        var missing = new PatentLibraryRecord.ContentSection("SOURCE_NOT_PROVIDED", List.of(), "https://example.test/" + id, "未采集");
        var version = new PatentLibraryRecord.DocumentVersion("v1", "CN-" + id, publicationDate,
                "公开文本", content, missing, missing, missing, "https://example.test/" + id);
        return new PatentLibraryRecord("PUBLIC_SOURCE", id, "v1", "APP-" + id, "CN-" + id, null,
                id + " 涂布工艺", type, List.of("申请人"), List.of("发明人"), null,
                publicationDate.minusYears(1), publicationDate, null, List.of(), List.of(ipc), List.of(), null,
                null, "来源未提供", null, "2026-09-18T00:00:00Z", "https://example.test/" + id,
                "PUBLIC_LOCAL", List.of(version));
    }
}
