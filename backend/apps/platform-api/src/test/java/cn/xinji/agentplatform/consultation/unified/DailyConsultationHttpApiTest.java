package cn.xinji.agentplatform.consultation.unified;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
@ActiveProfiles("test-synthetic")
class DailyConsultationHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void answersPatentSearchThroughTheSingleEntry() throws Exception {
        submit("daily-patent", "请检索水性粘结剂双区烘干技术方案，用于技术参考")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].candidates.length()").value(2))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("当前命中依据")))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("V0-SYN-PAT-001@v1")));
    }

    @Test void answersNaturalPatentTopicWordingWithoutFalseClarification() throws Exception {
        submit("daily-patent-natural", "检索双区烘干相关专利，用于技术参考")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"));
    }

    @Test void treatsTheReportedBareTechnicalTopicAsPatentSearch() throws Exception {
        submit("daily-bare-technical-topic", "水性粘结剂及其应用")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"));
    }

    @Test void answersTheReportedCpcTopicWithoutMisroutingToPatentComparison() throws Exception {
        submit("daily-cpc-topic", "基于 CPC 分类的专利相似度判断方法有几种")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].status").value(org.hamcrest.Matchers.not("NEEDS_CLARIFICATION")))
                .andExpect(jsonPath("$.results[0].candidates").isArray());
    }

    @Test void neverExposesInternalSlotNamesInClarificationText() throws Exception {
        submit("daily-comparison-missing", "请比对这两件专利是否相同")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("目标专利")))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("targetPatent"))))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("candidatePatent"))));
    }

    @Test void answersTrademarkSearchWithoutLeavingTheConversation() throws Exception {
        submit("daily-trademark", "检索“清源”商标，第3类化妆品")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].intentId").value("TRADEMARK_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].candidates[0].title").value("清源"))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("当前命中依据")))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("清源")))
                .andExpect(jsonPath("$.results[0].nextActionHref").value("/app/compare/trademarks"));
    }

    @Test void answersTrademarkSearchFromTheReportedBareNameAndSpacedCategory() throws Exception {
        submit("daily-trademark-bare", "检索清源商标，第 3 类化妆品")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].intentId").value("TRADEMARK_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("清源")))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("TM-SYN-001@synthetic-v1")));
    }

    @Test void answersCaseReferenceWithLocatedSources() throws Exception {
        submit("daily-case", "商标侵权二审有哪些可参考案例")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].intentId").value("CASE_REFERENCE"))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[0].summary").value(org.hamcrest.Matchers.containsString("当前命中依据")))
                .andExpect(jsonPath("$.results[0].sources").isArray());
    }

    @Test void keepsReadyAndPendingMultiIntentResultsSeparate() throws Exception {
        submit("daily-multi", "请检索水性粘结剂双区烘干技术方案，用于技术参考，并找可参考案例")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_RESULT"))
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].status").value("ANSWER_READY"))
                .andExpect(jsonPath("$.results[1].status").value("NEEDS_CLARIFICATION"));
    }

    @Test void preservesPartialPatentResultsInsteadOfReportingWholeTurnFailure() throws Exception {
        var parsed = new cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult(
                "RESOLVED", "LOCAL-INTENT-V1", "检索双区烘干相关专利", "PATENT_SIMILAR_SEARCH",
                java.util.List.of(), java.util.List.of(), "", "trace", java.time.Instant.EPOCH);
        var partial = new DailyConsultationResponse.RouteResult("PATENT_SIMILAR_SEARCH", "专利相似检索", "PATENT",
                "PARTIAL_RESULT", "task-1", "候选已返回，模型答复未生成", "本地专利范围", "需人工核对",
                "选择候选继续对照", "/app/compare/patents", java.util.List.of(), java.util.List.of());
        assertEquals("PARTIAL_RESULT", DailyConsultationService.overallStatus(parsed, java.util.List.of(partial)));
        assertEquals("已识别 1 项诉求，完成 0 项，1 项部分完成。",
                DailyConsultationService.summary(java.util.List.of(partial), java.util.List.of()));
    }

    @Test void reportsAnAllNoResultTurnAsNoResultRatherThanAnswerReady() {
        var parsed = new cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult(
                "RESOLVED", "LOCAL-INTENT-V1", "专利技术主题", "PATENT_SIMILAR_SEARCH",
                java.util.List.of(), java.util.List.of(), "", "trace", java.time.Instant.EPOCH);
        var noResult = new DailyConsultationResponse.RouteResult("PATENT_SIMILAR_SEARCH", "专利相似检索", "PATENT",
                "NO_RESULT", "task-2", "当前范围无候选", "本地专利范围", "需人工核对",
                "补充关键词后重试", "", java.util.List.of(), java.util.List.of());
        assertEquals("NO_RESULT", DailyConsultationService.overallStatus(parsed, java.util.List.of(noResult)));
        assertEquals("已完成检索，当前资料范围内未找到可支持回答的结果。",
                DailyConsultationService.summary(java.util.List.of(noResult), java.util.List.of()));
    }

    @Test void reportsMixedNoResultAndClarificationAsPartialRatherThanToolFailure() {
        var parsed = new cn.xinji.agentplatform.consultation.intent.domain.IntentParseResult(
                "MULTI_INTENT", "LOCAL-INTENT-V1", "复合问题", "TRADEMARK_SIMILAR_SEARCH",
                java.util.List.of(), java.util.List.of(), "", "trace", java.time.Instant.EPOCH);
        var noResult = new DailyConsultationResponse.RouteResult("PATENT_SIMILAR_SEARCH", "专利相似检索", "PATENT",
                "NO_RESULT", "task-3", "当前范围无候选", "本地专利范围", "需人工核对",
                "补充关键词后重试", "", java.util.List.of(), java.util.List.of());
        var pending = new DailyConsultationResponse.RouteResult("TRADEMARK_SIMILAR_SEARCH", "商标相似检索", "TRADEMARK",
                "NEEDS_CLARIFICATION", "", "需要商品服务", "", "未调用检索",
                "补充商品服务", "", java.util.List.of(), java.util.List.of());
        assertEquals("PARTIAL_RESULT", DailyConsultationService.overallStatus(parsed, java.util.List.of(noResult, pending)));
    }

    @Test void separatesPatentTopicFromPurposeBeforeRetrieval() {
        assertEquals("水性粘结剂及其应用",
                DailyConsultationToolRunner.normalizePatentInput("检索水性粘结剂及其应用，用于技术参考"));
    }

    @Test void doesNotCallToolsForUnknownOrIncompleteQuestions() throws Exception {
        submit("daily-unknown", "今天食堂有什么菜")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"));
        submit("daily-incomplete", "帮我做商标近似检索")
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.results[0].candidates.length()").value(0));
    }

    @Test void rejectsUnauthorizedAndInvalidRequests() throws Exception {
        mvc.perform(post("/api/v0/consultations/daily").header("X-V0-Requester", "unknown")
                        .header("Idempotency-Key", "daily-denied").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"检索专利技术方案，用于技术参考\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v0/consultations/daily").header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "daily-empty").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions submit(String key, String question) throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("question", question, "context", java.util.List.of()));
        return mvc.perform(post("/api/v0/consultations/daily").header("X-V0-Requester", "v0-authorized-user")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
