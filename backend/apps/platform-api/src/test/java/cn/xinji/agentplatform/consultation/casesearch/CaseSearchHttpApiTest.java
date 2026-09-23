package cn.xinji.agentplatform.consultation.casesearch;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.xinji.agentplatform.consultation.application.ConsultationAuthorizationPort;
import cn.xinji.agentplatform.consultation.casesearch.adapter.out.memory.InMemoryCaseSearchTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "case-search.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
@ContextConfiguration(classes = {cn.xinji.agentplatform.AgentPlatformApplication.class, CaseSearchHttpApiTest.AuthorizationConfig.class})
class CaseSearchHttpApiTest {
    private static final String USER = "v0-authorized-user";
    private static final String OTHER_USER = "v0-case-search-user";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InMemoryCaseSearchTaskRepository repository;

    @BeforeEach void clearTasks() { repository.clear(); }

    @Test void preparesKeywordsAndRequestsClarificationForBroadQuestions() throws Exception {
        mvc.perform(post("/api/v0/case-search-tasks/preparations")
                        .header("X-V0-Requester", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"商标问题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresClarification").value(true))
                .andExpect(jsonPath("$.suggestedKeywords[0]").value("商标"))
                .andExpect(jsonPath("$.issues[0]").exists());

        mvc.perform(post("/api/v0/case-search-tasks/preparations")
                        .header("X-V0-Requester", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"商标侵权二审有哪些案例\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresClarification").value(false))
                .andExpect(jsonPath("$.suggestedKeywords.length()").value(greaterThan(1)));
    }

    @Test void createsRankedTaskWithScopeAndSourceEvidence() throws Exception {
        String body = body("商标侵权", "[\"商标\",\"侵权\"]", "[\"商标\"]", "[]", "[]");
        JsonNode task = create(USER, "ranked-1", body, 202);

        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", task.path("status").asText());
        org.junit.jupiter.api.Assertions.assertTrue(task.path("totalCandidates").asInt() > 0);
        org.junit.jupiter.api.Assertions.assertEquals("SOURCE_TEXT", task.at("/candidates/0/evidence/state").asText());
        org.junit.jupiter.api.Assertions.assertFalse(task.at("/candidates/0/evidence/sourceLocation").asText().isBlank());
        org.junit.jupiter.api.Assertions.assertEquals("PUBLIC-LOCAL-CASE-TERM-RANK-V1", task.path("rankingVersion").asText());

        int previous = 101;
        for (JsonNode candidate : task.path("candidates")) {
            int current = candidate.path("relevanceScore").asInt();
            org.junit.jupiter.api.Assertions.assertTrue(current <= previous, "candidates must be score-descending");
            previous = current;
        }
    }

    @Test void filtersScopeAndRepresentsNoResultWithoutClaimingAbsence() throws Exception {
        JsonNode filtered = create(USER, "scope-1",
                body("商标争议", "[\"商标\"]", "[\"商标\"]", "[\"宁江市中级人民法院\"]", "[\"一审\"]"), 202);
        for (JsonNode candidate : filtered.path("candidates")) {
            org.junit.jupiter.api.Assertions.assertEquals("商标", candidate.path("intellectualPropertyType").asText());
            org.junit.jupiter.api.Assertions.assertEquals("宁江市中级人民法院", candidate.path("institution").asText());
            org.junit.jupiter.api.Assertions.assertEquals("一审", candidate.path("procedure").asText());
        }

        JsonNode empty = create(USER, "empty-1",
                body("完全不存在的检索词", "[\"完全不存在的检索词\"]", "[]", "[]", "[]"), 202);
        org.junit.jupiter.api.Assertions.assertEquals("NO_RESULT", empty.path("status").asText());
        org.junit.jupiter.api.Assertions.assertEquals(0, empty.path("totalCandidates").asInt());
        org.junit.jupiter.api.Assertions.assertTrue(empty.path("limitation").asText().contains("不是经专家标定"));
    }

    @Test void replaysSameRequestAndRejectsChangedPayloadForSameKey() throws Exception {
        String body = body("专利侵权", "[\"专利\",\"侵权\"]", "[\"专利\"]", "[]", "[]");
        JsonNode first = create(USER, "idem-1", body, 202);
        JsonNode replay = create(USER, "idem-1", body, 202);
        org.junit.jupiter.api.Assertions.assertEquals(first.path("taskId").asText(), replay.path("taskId").asText());

        mvc.perform(post("/api/v0/case-search-tasks").header("X-V0-Requester", USER)
                        .header("Idempotency-Key", "idem-1").contentType(MediaType.APPLICATION_JSON)
                        .content(body("商标侵权", "[\"商标\"]", "[]", "[]", "[]")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void enforcesAccessOwnershipAndRequesterScopedHistory() throws Exception {
        JsonNode task = create(USER, "owner-1", body("著作权侵权", "[\"著作权\",\"侵权\"]", "[]", "[]", "[]"), 202);
        create(OTHER_USER, "owner-2", body("专利侵权", "[\"专利\"]", "[]", "[]", "[]"), 202);

        mvc.perform(get("/api/v0/case-search-tasks/{id}", task.path("taskId").asText())
                        .header("X-V0-Requester", OTHER_USER))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        mvc.perform(get("/api/v0/case-search-tasks").header("X-V0-Requester", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requester").value(USER));
        mvc.perform(get("/api/v0/case-search-tasks").header("X-V0-Requester", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    private JsonNode create(String user, String key, String body, int expectedStatus) throws Exception {
        byte[] response = mvc.perform(post("/api/v0/case-search-tasks").header("X-V0-Requester", user)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(new String(response, StandardCharsets.UTF_8));
    }

    private static String body(String question, String keywords, String ipTypes, String institutions, String procedures) {
        return "{\"question\":\"" + question + "\",\"confirmedKeywords\":" + keywords
                + ",\"caseTypes\":[],\"intellectualPropertyTypes\":" + ipTypes
                + ",\"institutions\":" + institutions + ",\"procedures\":" + procedures
                + ",\"decisionFrom\":null,\"decisionTo\":null,\"resultLimit\":20}";
    }

    @TestConfiguration
    static class AuthorizationConfig {
        @Bean @Primary ConsultationAuthorizationPort caseSearchTestAuthorization() {
            return requester -> USER.equals(requester) || OTHER_USER.equals(requester);
        }
    }
}
