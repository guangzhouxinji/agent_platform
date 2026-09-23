package cn.xinji.agentplatform.consultation;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.adapter.out.mockcenter.MockCenterConsultationPatentSearchAdapter;
import cn.xinji.agentplatform.consultation.application.PatentSearchPort;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterCatalogClient;
import cn.xinji.agentplatform.consultation.patentsearch.adapter.out.mockcenter.MockCenterPatentCatalogAdapter;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class MockCenterConsultationPatentSearchAdapterTest {
    @Test void extractsOnlyUserInputAndMapsMockCenterCandidates() {
        RecordingAnalysis analysis = new RecordingAnalysis(true, List.of("水性粘结剂", "双区烘干"));
        RecordingCatalog catalog = new RecordingCatalog(List.of(record()));
        var adapter = new MockCenterConsultationPatentSearchAdapter(new MockCenterPatentCatalogAdapter(catalog), analysis);

        var result = adapter.search("水性粘结剂电极采用双区烘干");

        assertEquals("水性粘结剂电极采用双区烘干", analysis.input);
        assertEquals("TECHNICAL_REFERENCE", analysis.purpose);
        assertEquals(List.of("水性粘结剂", "双区烘干"), catalog.queries);
        assertEquals(PatentSearchPort.SearchResult.Type.SUCCESS, result.type());
        assertEquals("MCP-1", result.candidates().getFirst().sourceId());
        assertEquals("public-v1", result.candidates().getFirst().version());
        assertTrue(result.candidates().getFirst().matchBasis().contains("CN-TEST-1-A"));
        assertTrue(result.candidates().getFirst().matchBasis().contains("命中特征：水性粘结剂、双区烘干"));
        assertTrue(result.actualScope().contains("数据模式=PUBLIC_PILOT"));
        assertTrue(result.limitation().contains("非中心权威数据"));
        assertTrue(adapter.isSourceAvailable("MCP-1", "public-v1"));
        assertFalse(adapter.isSourceAvailable("MCP-1", "other-version"));
    }

    @Test void reportsModelFailureWithoutQueryingCatalogOrReturningFixedCandidates() {
        RecordingAnalysis analysis = new RecordingAnalysis(false, List.of());
        RecordingCatalog catalog = new RecordingCatalog(List.of(record()));

        var result = new MockCenterConsultationPatentSearchAdapter(new MockCenterPatentCatalogAdapter(catalog), analysis).search("水性粘结剂");

        assertEquals(PatentSearchPort.SearchResult.Type.TOOL_FAILURE, result.type());
        assertTrue(result.candidates().isEmpty());
        assertTrue(catalog.queries.isEmpty());
        assertTrue(result.limitation().contains("DeepSeek 未配置"));
    }

    @Test void keepsEmptyMockCenterResultDistinctFromToolFailure() {
        RecordingAnalysis analysis = new RecordingAnalysis(true, List.of("不存在的特征"));
        RecordingCatalog catalog = new RecordingCatalog(List.of());

        var result = new MockCenterConsultationPatentSearchAdapter(new MockCenterPatentCatalogAdapter(catalog), analysis).search("不存在的方案");

        assertEquals(PatentSearchPort.SearchResult.Type.NO_RESULT, result.type());
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.limitation().contains("不等于不存在相关技术"));
    }

    private static MockCenterCatalogClient.PatentRecord record() {
        return new MockCenterCatalogClient.PatentRecord("MCP-1", "PUBLIC_TEST", "SRC-1", "public-v1",
                "CN-TEST-1-A", "CN-TEST-1", "水性粘结剂电极双区烘干方法", List.of("试验申请人"), List.of(),
                LocalDate.parse("2023-01-01"), LocalDate.parse("2024-01-01"), List.of("H01M4/04"),
                "电极涂布后使用水性粘结剂和双区烘干", "PARTIAL", "https://example.test/CN-TEST-1-A",
                "2026-09-17T00:00:00Z", "a".repeat(64), "mock-v1");
    }

    private static final class RecordingAnalysis implements PatentAnalysisPort {
        private final boolean configured;
        private final List<String> features;
        private String input;
        private String purpose;
        private RecordingAnalysis(boolean configured, List<String> features) { this.configured = configured; this.features = features; }
        @Override public ModelStatus status() { return new ModelStatus("deepseek", "test-model", configured); }
        @Override public String analyze(String question, List<PatentAssistantSource> sources, String traceId) { throw new UnsupportedOperationException(); }
        @Override public FeatureExtraction extractFeatures(String input, String purpose, String traceId) {
            this.input = input;
            this.purpose = purpose;
            return new FeatureExtraction(features);
        }
    }

    private static final class RecordingCatalog implements MockCenterCatalogClient {
        private final List<PatentRecord> records;
        private final List<String> queries = new ArrayList<>();
        private RecordingCatalog(List<PatentRecord> records) { this.records = records; }
        @Override public Metadata metadata() { return new Metadata(records.size(), "mock-v1", "batch-1"); }
        @Override public Page search(String text, String publicationNumber, int page, int pageSize) {
            queries.add(text);
            List<PatentRecord> matches = records.stream().filter(record -> text != null
                    && (record.title().contains(text) || record.abstractText().contains(text))).toList();
            return new Page(matches, 1, 50, matches.size());
        }
        @Override public Optional<PatentRecord> find(String recordId) {
            return records.stream().filter(record -> record.recordId().equals(recordId)).findFirst();
        }
    }
}
