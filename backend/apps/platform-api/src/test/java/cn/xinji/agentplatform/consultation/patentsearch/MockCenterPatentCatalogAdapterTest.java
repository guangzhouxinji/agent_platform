package cn.xinji.agentplatform.consultation.patentsearch;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterCatalogClient;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentsearch.domain.PatentSearchCommand;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockCenterPatentCatalogAdapterTest {
    @Test void mergesFeatureQueriesAndDisclosesMissingCapabilities() {
        var catalog = new RecordingCatalog();
        var adapter = new MockCenterPatentCatalogAdapter(catalog);
        var scope = new PatentSearchCommand.SearchScope(List.of("发明"), List.of("CN"), List.of("H01M"),
                null, null, LocalDate.parse("2024-01-01"), null);
        var command = new PatentSearchCommand(PatentSearchCommand.InputMode.TECHNICAL_SOLUTION,
                "水性粘结剂双区烘干", "TECHNICAL_REFERENCE", List.of("水性粘结剂", "双区烘干"), scope);

        var result = adapter.search(command);

        assertEquals(List.of("水性粘结剂", "双区烘干"), catalog.queries);
        assertEquals(1, result.candidates().size());
        assertEquals("MCP-1", result.candidates().getFirst().sourceId());
        assertEquals("SOURCE_NOT_PROVIDED", result.candidates().getFirst().patentType());
        assertEquals("公开摘要", result.candidates().getFirst().location());
        assertTrue(result.partial());
        assertTrue(result.actualScope().contains("专利类型=未执行"));
        assertTrue(result.limitation().contains("未检索全文或权利要求"));
        assertEquals("PUBLIC_PILOT", adapter.descriptor().dataMode());
    }

    @Test void deduplicatesFeaturePagesCapsCandidatesAndKeepsTieOrderStable() {
        List<MockCenterCatalogClient.PatentRecord> records = new ArrayList<>();
        for (int index = 24; index >= 0; index--) {
            String id = "MCP-" + String.format("%02d", index);
            records.add(new MockCenterCatalogClient.PatentRecord(id, "PUBLIC", "SRC-" + id, "v1",
                    "CN" + index + "A", null, "共同特征专利", List.of(), List.of(), null,
                    LocalDate.parse("2024-01-01"), List.of(), "共同特征摘要", "PARTIAL",
                    "https://example.test/" + id, "2026-09-17T00:00:00Z", "a".repeat(64), "mock-v1"));
        }
        MockCenterCatalogClient catalog = new MockCenterCatalogClient() {
            @Override public Metadata metadata() { return new Metadata(records.size(), "mock-v1", "batch"); }
            @Override public Page search(String text, String publicationNumber, int page, int pageSize) { return new Page(records, 1, 50, records.size()); }
            @Override public Optional<PatentRecord> find(String recordId) { return Optional.empty(); }
        };
        var result = new MockCenterPatentCatalogAdapter(catalog).search(new PatentSearchCommand(
                PatentSearchCommand.InputMode.TECHNICAL_SOLUTION, "共同特征", "TECHNICAL_REFERENCE",
                List.of("共同特征", "特征"), PatentSearchCommand.SearchScope.empty()));

        assertEquals(20, result.candidates().size());
        assertEquals(20, result.candidates().stream().map(candidate -> candidate.sourceId()).distinct().count());
        assertEquals("MCP-00", result.candidates().getFirst().sourceId());
        assertEquals("MCP-19", result.candidates().getLast().sourceId());
    }

    private static final class RecordingCatalog implements MockCenterCatalogClient {
        private final List<String> queries = new ArrayList<>();
        private final PatentRecord record = new PatentRecord("MCP-1", "PUBLIC", "SRC-1", "v1", "CN1A", "CN1",
                "水性粘结剂电极涂布方法", List.of("申请人"), List.of(), LocalDate.parse("2023-01-01"),
                LocalDate.parse("2024-01-01"), List.of("H01M4/04"), "水性粘结剂经双区烘干完成涂布", "PARTIAL",
                "https://example.test/CN1A", "2026-09-17T00:00:00Z", "a".repeat(64), "mock-v1");
        @Override public Metadata metadata() { return new Metadata(1, "mock-v1", "batch-1"); }
        @Override public Page search(String text, String publicationNumber, int page, int pageSize) { queries.add(text); return new Page(List.of(record), 1, 50, 1); }
        @Override public Optional<PatentRecord> find(String recordId) { return Optional.of(record); }
    }
}
