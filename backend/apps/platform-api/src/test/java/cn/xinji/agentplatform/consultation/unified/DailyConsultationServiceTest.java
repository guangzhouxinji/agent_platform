package cn.xinji.agentplatform.consultation.unified;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.xinji.agentplatform.consultation.intent.application.ConsultationIntentParserService;
import cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyConsultationServiceTest {
    private final ConsultationIntentParserService parser = mock(ConsultationIntentParserService.class);
    private final DailyConsultationToolRunner tools = mock(DailyConsultationToolRunner.class);
    private final ExternalModelIntentAdvisor model = mock(ExternalModelIntentAdvisor.class);
    private final DailyIntentContractCatalog contracts = new DailyIntentContractCatalog(new ObjectMapper());
    private final DailyConsultationService service = new DailyConsultationService(parser, tools, model, contracts);

    @Test
    void executesAValidatedModelCandidateWhenFixedParsingCannotResolveTheQuestion() {
        when(parser.parse(anyString(), any())).thenReturn(parsed("UNKNOWN", "LOCAL-INTENT-V1",
                candidate("UNKNOWN", "未识别", "GENERAL", List.of(), List.of("consultationPurpose"), null, "NOT_READY")));
        when(model.advise(anyString(), any(), anyString())).thenReturn(List.of(
                new DailyIntentContractCatalog.IntentAdvice("CASE_REFERENCE", Map.of("specificIssueOrFacts", "商标侵权二审"))));
        when(tools.run(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            IntentParseResult.IntentCandidate intent = invocation.getArgument(3);
            assertEquals("CASE_REFERENCE", intent.intentId());
            assertEquals("商标侵权二审", intent.slots().getFirst().value());
            return completed(intent);
        });

        DailyConsultationResponse response = service.submit("v0-authorized-user", "model-route",
                new DailyConsultationRequest("请找与刚才争议相关的案例", List.of()));

        assertEquals("MODEL_CANDIDATE_CONFIRMED", response.intentResolutionSource());
        assertEquals("CASE_REFERENCE", response.primaryIntentId());
        assertTrue(response.parserVersion().contains(contracts.version()));
        verify(tools).run(anyString(), anyString(), anyString(), any());
    }

    @Test
    void preservesTheFixedParserRouteWhenTheModelReturnsNoUsableCandidate() {
        IntentParseResult.IntentCandidate fixed = candidate("PATENT_INFORMATION", "专利信息查询", "PATENT",
                List.of(new IntentParseResult.ExtractedSlot("patentNumber", "CN108276937A", 0, "USER_INPUT")),
                List.of(), "/libraries/patents", "AVAILABLE");
        when(parser.parse(anyString(), any())).thenReturn(parsed("RESOLVED", "LOCAL-INTENT-V1", fixed));
        when(model.advise(anyString(), any(), anyString())).thenReturn(List.of());
        when(tools.run(anyString(), anyString(), anyString(), any())).thenReturn(completed(fixed));

        DailyConsultationResponse response = service.submit("v0-authorized-user", "fixed-route",
                new DailyConsultationRequest("查询 CN108276937A", List.of()));

        assertEquals("FIXED_RULE_FALLBACK", response.intentResolutionSource());
        assertEquals("LOCAL-INTENT-V1", response.parserVersion());
        assertEquals("PATENT_INFORMATION", response.results().getFirst().intentId());
        verify(tools).run(anyString(), anyString(), anyString(), any());
    }

    private static IntentParseResult parsed(String status, String version, IntentParseResult.IntentCandidate candidate) {
        return new IntentParseResult(status, version, "问题", candidate.intentId(), List.of(candidate), List.of(),
                "开发期限制", "trace-1", Instant.EPOCH);
    }

    private static IntentParseResult.IntentCandidate candidate(String id, String label, String category,
            List<IntentParseResult.ExtractedSlot> slots, List<String> missing, String route, String routeState) {
        return new IntentParseResult.IntentCandidate(id, label, category, 1, List.of(), slots, missing, route, routeState, "测试解析");
    }

    private static DailyConsultationResponse.RouteResult completed(IntentParseResult.IntentCandidate intent) {
        return new DailyConsultationResponse.RouteResult(intent.intentId(), intent.label(), intent.category(), "ANSWER_READY",
                "task-1", "已完成", "本地资料", "需人工复核", "查看来源", "", List.of(), List.of());
    }
}
