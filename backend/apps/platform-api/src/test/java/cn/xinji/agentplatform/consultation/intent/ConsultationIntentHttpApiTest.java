package cn.xinji.agentplatform.consultation.intent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class ConsultationIntentHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void resolvesPatentInformationFromNumberShapeWithoutKeywordOnlyRouting() throws Exception {
        parse("{\"question\":\"查询 CN116234567A 的申请人和状态\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.primaryIntentId").value("PATENT_INFORMATION"))
                .andExpect(jsonPath("$.intents[0].slots[0].name").value("patentNumber"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/libraries/patents"));
    }

    @Test void returnsMissingSlotsInsteadOfInventingTrademarkFacts() throws Exception {
        parse("{\"question\":\"帮我做商标近似检索\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.intents[0].intentId").value("TRADEMARK_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").doesNotExist())
                .andExpect(jsonPath("$.intents[0].missingSlots[0]").value("trademarkName"));
    }

    @Test void resolvesConcreteCaseReferenceToTheImplementedCaseSearch() throws Exception {
        parse("{\"question\":\"商标侵权二审有哪些可参考案例\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.primaryIntentId").value("CASE_REFERENCE"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/search/cases"));
    }

    @Test void extractsAVisibleTechnicalTopicFromNaturalPatentSearchWording() throws Exception {
        parse("{\"question\":\"检索双区烘干相关专利，用于技术参考\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.intents[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.intents[0].slots[?(@.name == 'technicalInput')].value").value("双区烘干"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/search/patents"));
    }

    @Test void treatsSimilarityJudgmentMethodAsATechnicalTopicNotTwoPatentComparison() throws Exception {
        parse("{\"question\":\"基于 CPC 分类的专利相似度判断方法有几种\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.intents[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.intents[0].missingSlots").isEmpty())
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/search/patents"));
    }

    @Test void keepsExplicitTwoPatentComparisonBehindConfirmedObjects() throws Exception {
        parse("{\"question\":\"比对 CN116234567A 和 CN116765432A 是否落入保护范围，用于侵权风险分析\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.intents[0].intentId").value("PATENT_COMPARISON"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/compare/patents"));
    }

    @Test void stillRequestsTechnicalInputForAGenericPatentSearch() throws Exception {
        parse("{\"question\":\"帮我检索相关专利，用于技术参考\",\"context\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.intents[0].missingSlots[0]").value("technicalInputOrPatentNumber"));
    }

    @Test void returnsOrderedIndependentIntentsAndKeepsContextSourceRound() throws Exception {
        parse("""
                {"question":"查询这个专利的状态；同时找相关侵权案例",
                 "context":[{"round":2,"content":"专利号是 CN116234567A"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MULTI_INTENT"))
                .andExpect(jsonPath("$.intents.length()").value(2))
                .andExpect(jsonPath("$.intents[0].intentId").value("PATENT_INFORMATION"))
                .andExpect(jsonPath("$.intents[0].slots[0].sourceRound").value(2))
                .andExpect(jsonPath("$.intents[1].intentId").value("CASE_REFERENCE"));
    }

    @Test void keepsAnExecutablePatentRouteWhenTheSeparateCaseRequestNeedsFacts() throws Exception {
        parse("""
                {"question":"请检索水性粘结剂双区烘干技术方案，用于技术参考，并找可参考案例", "context":[]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.intents.length()").value(2))
                .andExpect(jsonPath("$.intents[0].intentId").value("PATENT_SIMILAR_SEARCH"))
                .andExpect(jsonPath("$.intents[0].plannedRoute").value("/search/patents"))
                .andExpect(jsonPath("$.intents[1].intentId").value("CASE_REFERENCE"))
                .andExpect(jsonPath("$.intents[1].missingSlots[0]").value("specificIssueOrFacts"));
    }

    @Test void separatesUnknownAndUnavailableRoutes() throws Exception {
        parse("{\"question\":\"今天食堂有什么菜\",\"context\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.primaryIntentId").doesNotExist());
        parse("{\"question\":\"申请专利需要走什么办理流程\",\"context\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ROUTE_UNAVAILABLE"))
                .andExpect(jsonPath("$.intents[0].routeState").value("UNAVAILABLE"));
    }

    @Test void rejectsUnauthorizedAndInvalidRequests() throws Exception {
        mvc.perform(post("/api/v0/consultation-intents/parse").header("X-V0-Requester", "unknown")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"查询专利\",\"context\":[]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        parse("{\"question\":\"   \",\"context\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions parse(String body) throws Exception {
        return mvc.perform(post("/api/v0/consultation-intents/parse").header("X-V0-Requester", "v0-authorized-user")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
