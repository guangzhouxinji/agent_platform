package cn.xinji.agentplatform.consultation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.xinji.agentplatform.consultation.adapter.out.publiclocal.PublicLocalConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.patentlibrary.application.PatentLibraryPort;
import cn.xinji.agentplatform.consultation.patentlibrary.domain.PatentLibraryRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicLocalConsultationPatentSearchAdapterTest {
    @Test
    void searchesDatabaseFieldsAndDisclosesScopeAndVersion() {
        var adapter = new PublicLocalConsultationPatentSearchAdapter(library());

        var result = adapter.search("柔性电极 水性粘结剂");

        assertEquals(PatentSearchPort.SearchResult.Type.SUCCESS, result.type());
        assertEquals("DB-PAT-1", result.candidates().getFirst().sourceId());
        assertEquals("db-v1", result.candidates().getFirst().version());
        assertTrue(result.candidates().getFirst().location().contains("摘要"));
        assertTrue(result.actualScope().contains("PUBLIC_LOCAL / public-local-dm8-v1 / 2 件专利"));
        assertTrue(result.limitation().contains("非中心权威全库"));
        assertTrue(adapter.isSourceAvailable("DB-PAT-1", "db-v1"));
        assertFalse(adapter.isSourceAvailable("DB-PAT-1", "stale-version"));
    }

    @Test
    void returnsNoResultWithoutInventingCandidates() {
        var result = new PublicLocalConsultationPatentSearchAdapter(library()).search("不存在的量子输送装置");

        assertEquals(PatentSearchPort.SearchResult.Type.NO_RESULT, result.type());
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.limitation().contains("不等于不存在相关技术"));
    }

    private static PatentLibraryPort library() {
        List<PatentLibraryRecord> records = List.of(
                record("DB-PAT-1", "db-v1", "柔性电极涂布设备", "使用水性粘结剂并在双温区烘干", "权利要求包括在线测厚", List.of("B05C9/00"), List.of("新技术公司")),
                record("DB-PAT-2", "db-v2", "光学检测装置", "用于玻璃表面检测", "包括摄像单元", List.of("G01N21/00"), List.of("光学公司")));
        return new PatentLibraryPort() {
            @Override public List<PatentLibraryRecord> records() { return records; }
            @Override public Optional<PatentLibraryRecord> find(String sourceId) {
                return records.stream().filter(value -> value.sourceRecordId().equals(sourceId)).findFirst();
            }
            @Override public String sourceVersion() { return "public-local-dm8-v1"; }
            @Override public String dataMode() { return "PUBLIC_LOCAL"; }
            @Override public PatentLibraryRecord.EventResult events(String sourceId) {
                return new PatentLibraryRecord.EventResult("SOURCE_NOT_PROVIDED", List.of(), "未采集");
            }
        };
    }

    private static PatentLibraryRecord record(String id, String version, String title, String abstractText,
                                               String claimsText, List<String> ipc, List<String> applicants) {
        var abstractSection = section("ABS-001", abstractText);
        var claimsSection = section("CLM-001", claimsText);
        var missing = new PatentLibraryRecord.ContentSection("SOURCE_NOT_PROVIDED", List.of(), "https://example.test", "未采集");
        var document = new PatentLibraryRecord.DocumentVersion(version, "CN-" + id, LocalDate.of(2024, 1, 1),
                "本地公开数据快照", abstractSection, claimsSection, missing, missing, "https://example.test");
        return new PatentLibraryRecord("PUBLIC_SOURCE", id, version, "APP-" + id, "CN-" + id, null,
                title, "发明专利", applicants, List.of("发明人"), null, LocalDate.of(2023, 1, 1),
                LocalDate.of(2024, 1, 1), null, List.of(), ipc, List.of(), null, null, "来源未提供", null,
                "2024-01-01T00:00:00Z", "https://example.test", "PUBLIC_LOCAL", List.of(document));
    }

    private static PatentLibraryRecord.ContentSection section(String locator, String text) {
        return new PatentLibraryRecord.ContentSection("PARTIAL",
                List.of(new PatentLibraryRecord.LocatedText("公开来源节选", locator, text)),
                "https://example.test", "公开来源节选");
    }
}
