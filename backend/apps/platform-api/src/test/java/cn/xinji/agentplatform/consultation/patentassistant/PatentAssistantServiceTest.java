package cn.xinji.agentplatform.consultation.patentassistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAnalysisPort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantService;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSourcePort;
import cn.xinji.agentplatform.consultation.patentassistant.application.PatentAssistantSources;
import cn.xinji.agentplatform.consultation.patentassistant.domain.PatentAssistantSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatentAssistantServiceTest {
    private static final PatentAssistantSource SOURCE = new PatentAssistantSource("P1", "PUBLIC-1", "CN1A", "CN1",
            "公开候选", "申请人", "2024-01-01", "https://example.test/CN1A", "PARTIAL", "水性粘结剂", "公开摘要");

    @Test
    void returnsOnlyCitedModelAnalysis() {
        var service = service(new FixedModel("候选摘要支持该技术方向[P1]"), List.of(SOURCE));
        var result = service.analyze("v0-authorized-user", "水性粘结剂涂布工艺");
        assertEquals("候选摘要支持该技术方向[P1]", result.analysis());
        assertEquals("PUBLIC_PILOT", result.dataMode());
    }

    @Test
    void rejectsFabricatedCitation() {
        var service = service(new FixedModel("另有候选[P9]"), List.of(SOURCE));
        assertThrows(PatentAssistantService.InvalidModelOutputException.class,
                () -> service.analyze("v0-authorized-user", "水性粘结剂涂布工艺"));
    }

    @Test
    void rejectsUnauthorizedRequesterBeforeCallingModel() {
        var service = service(new FixedModel("候选[P1]"), List.of(SOURCE));
        assertThrows(PatentAssistantService.AccessDeniedException.class,
                () -> service.analyze("unknown", "水性粘结剂涂布工艺"));
    }

    @Test
    void doesNotCallModelWithoutPublicCandidate() {
        var model = new FixedModel("候选[P1]");
        var service = service(model, List.of());
        assertThrows(PatentAssistantService.NoCandidateException.class,
                () -> service.analyze("v0-authorized-user", "量子星际航行推进器"));
        assertEquals(0, model.calls);
    }

    @Test
    void blocksDynamicSourceBeforeCallingExternalModel() {
        var model = new FixedModel("候选[P1]");
        var service = service(model, List.of(SOURCE), false);
        assertThrows(PatentAssistantService.ExternalModelUseNotAllowedException.class,
                () -> service.analyze("v0-authorized-user", "水性粘结剂涂布工艺"));
        assertEquals(0, model.calls);
    }

    @Test
    void returnsSearchResultsWithoutCallingModel() {
        var model = new FixedModel("候选[P1]");
        var result = service(model, List.of(SOURCE), false)
                .search("v0-authorized-user", "水性粘结剂涂布工艺", 2, 5, "cn");
        assertEquals("surechembl", result.provider());
        assertEquals(1, result.sources().size());
        assertEquals(2, result.page());
        assertEquals(5, result.pageSize());
        assertEquals("CN", result.appliedCountry());
        assertEquals(0, model.calls);
    }

    @Test
    void keepsApprovedAnalysisCandidatesSeparateFromDynamicSearchCandidates() {
        var dynamic = new PatentAssistantSource("P1", "DYNAMIC-1", "CN-DYNAMIC-A", null,
                "动态候选", "动态申请人", "2025-01-01", "https://example.test/dynamic",
                "SOURCE_NOT_PROVIDED", "动态检索", null);
        var analysisSource = new CountingSource("public-static", true, List.of(SOURCE));
        var searchSource = new CountingSource("surechembl", false, List.of(dynamic));
        var model = new FixedModel("只引用静态公开样本[P1]");
        var service = new PatentAssistantService("v0-authorized-user"::equals,
                new PatentAssistantSources(analysisSource, searchSource), model);

        service.analyze("v0-authorized-user", "水性粘结剂涂布工艺");
        var search = service.search("v0-authorized-user", "水性粘结剂涂布工艺", 1, 5, "CN");

        assertEquals("PUBLIC-1", model.lastSources.getFirst().sourceRecordId());
        assertEquals("DYNAMIC-1", search.sources().getFirst().sourceRecordId());
        assertEquals(1, analysisSource.searchCalls);
        assertEquals(1, searchSource.searchCalls);
    }

    private static PatentAssistantService service(FixedModel model, List<PatentAssistantSource> sources) {
        return service(model, sources, true);
    }

    private static PatentAssistantService service(FixedModel model, List<PatentAssistantSource> sources,
                                                   boolean externalModelUseAllowed) {
        PatentAssistantSourcePort source = new PatentAssistantSourcePort() {
            @Override public SearchPage search(SearchRequest request) {
                return new SearchPage(List.of("水性粘结剂"), sources, request.page(), request.pageSize(),
                        (long) sources.size(), false);
            }
            @Override public SourceDetail detail(String publicationNumber) {
                return new SourceDetail(publicationNumber, "PARTIAL", "公开摘要");
            }
            @Override public SourceStatus status() {
                return new SourceStatus(externalModelUseAllowed ? "public-static" : "surechembl",
                        externalModelUseAllowed ? "PUBLIC_PILOT" : "PUBLIC_DYNAMIC", "v0.1", sources.size(),
                        externalModelUseAllowed, "NON_CENTER_PUBLIC_SOURCE", "INTERNAL_DEVELOPMENT_PILOT_ONLY");
            }
        };
        return new PatentAssistantService("v0-authorized-user"::equals, new PatentAssistantSources(source, source), model);
    }

    private static final class FixedModel implements PatentAnalysisPort {
        private final String output;
        private int calls;
        private List<PatentAssistantSource> lastSources = List.of();
        private FixedModel(String output) { this.output = output; }
        @Override public ModelStatus status() { return new ModelStatus("deepseek", "deepseek-chat", true); }
        @Override public String analyze(String question, List<PatentAssistantSource> sources, String traceId) {
            calls++;
            lastSources = List.copyOf(sources);
            return output;
        }
        @Override public FeatureExtraction extractFeatures(String input, String purpose, String traceId) {
            return new FeatureExtraction(List.of("知识图谱"));
        }
    }

    private static final class CountingSource implements PatentAssistantSourcePort {
        private final String provider;
        private final boolean externalModelUseAllowed;
        private final List<PatentAssistantSource> candidates;
        private int searchCalls;

        private CountingSource(String provider, boolean externalModelUseAllowed,
                               List<PatentAssistantSource> candidates) {
            this.provider = provider;
            this.externalModelUseAllowed = externalModelUseAllowed;
            this.candidates = candidates;
        }

        @Override public SearchPage search(SearchRequest request) {
            searchCalls++;
            return new SearchPage(List.of("水性粘结剂"), candidates, request.page(), request.pageSize(),
                    (long) candidates.size(), false);
        }
        @Override public SourceDetail detail(String publicationNumber) {
            return new SourceDetail(publicationNumber, "SOURCE_NOT_PROVIDED", null);
        }
        @Override public SourceStatus status() {
            return new SourceStatus(provider, provider.equals("public-static") ? "PUBLIC_PILOT" : "PUBLIC_DYNAMIC",
                    "v1", candidates.size(), externalModelUseAllowed, "NON_CENTER_PUBLIC_SOURCE",
                    "INTERNAL_DEVELOPMENT_PILOT_ONLY");
        }
    }
}
