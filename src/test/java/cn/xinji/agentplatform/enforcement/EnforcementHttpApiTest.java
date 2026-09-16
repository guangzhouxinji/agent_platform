package cn.xinji.agentplatform.enforcement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "enforcement.storage=memory",
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class EnforcementHttpApiTest {
    private static final String USER = "v0-enforcement-user";
    private static final String REVIEWER = "v0-enforcement-reviewer";
    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsProcessesAndReadsSyntheticWork() throws Exception {
        JsonNode opened = create("http-open-1", "V0-SYN-CLUE-001");
        String workId = opened.at("/data/workItem/workItemId").asText();
        JsonNode processed = mapper.readTree(mvc.perform(post("/api/v0/enforcement/work-items/{id}/process", workId)
                        .header("X-V0-Actor", USER).header("Idempotency-Key", "http-process-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceIds\":[\"V0-SYN-EVD-001\",\"V0-SYN-EVD-002\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("AUXILIARY_ANALYSIS_READY"))
                .andExpect(jsonPath("$.limitations[1]").value("NOT_OFFICIAL_DECISION"))
                .andReturn().getResponse().getContentAsString());
        String analysisId = processed.at("/data/clueAnalyses/0/analysisId").asText();
        mvc.perform(get("/api/v0/enforcement/work-items/{id}", workId).header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidenceReferences.length()").value(2));
        mvc.perform(get("/api/v0/enforcement/work-items/{id}/clue-analyses/{analysisId}/versions/1", workId, analysisId)
                        .header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.analysisId").value(analysisId));
    }

    @Test
    void revisesAndReviewsExactVersions() throws Exception {
        String workId = create("http-open-2", "V0-SYN-CLUE-001").at("/data/workItem/workItemId").asText();
        JsonNode processed = process(workId, "http-process-2", "V0-SYN-EVD-001");
        String clueId = processed.at("/data/clueAnalyses/0/analysisId").asText();
        String analysisId = processed.at("/data/auxiliaryAnalyses/0/analysisResultId").asText();
        mvc.perform(post("/api/v0/enforcement/work-items/{id}/clue-analyses/{analysisId}/actions", workId, clueId)
                        .header("X-V0-Actor", REVIEWER).header("Idempotency-Key", "http-revise-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REVISE\",\"expectedVersion\":1,\"reason\":\"checked\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.clueAnalyses.length()").value(2));
        mvc.perform(post("/api/v0/enforcement/work-items/{id}/auxiliary-analyses/{analysisId}/actions", workId, analysisId)
                        .header("X-V0-Actor", REVIEWER).header("Idempotency-Key", "http-review-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM\",\"expectedVersion\":1,\"reason\":\"synthetic check\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("REVIEW_RECORDED"))
                .andExpect(jsonPath("$.data.reviews[0].notOfficialDecision").value(true));
    }

    @Test
    void scopesIdempotencyByOperationAndRejectsChangedInput() throws Exception {
        String workId = create("shared-key-3", "V0-SYN-CLUE-001").at("/data/workItem/workItemId").asText();
        process(workId, "shared-key-3", "V0-SYN-EVD-001");
        mvc.perform(post("/api/v0/enforcement/work-items")
                        .header("X-V0-Actor", USER).header("X-V0-Organization", "org-v0")
                        .header("Idempotency-Key", "shared-key-3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-002\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void deniesUnknownIdentityAndProtectedEvidence() throws Exception {
        mvc.perform(post("/api/v0/enforcement/work-items").header("X-V0-Organization", "org-v0")
                        .header("Idempotency-Key", "missing-identity").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        String workId = create("http-open-4", "V0-SYN-CLUE-001").at("/data/workItem/workItemId").asText();
        mvc.perform(post("/api/v0/enforcement/work-items/{id}/process", workId)
                        .header("X-V0-Actor", USER).header("Idempotency-Key", "http-process-4")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"evidenceIds\":[\"V0-SYN-EVD-005\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void readsOnlyControlledSourceMetadata() throws Exception {
        JsonNode opened = create("http-open-5", "V0-SYN-CLUE-001");
        String workId = opened.at("/data/workItem/workItemId").asText();
        String sourceId = opened.at("/data/workItem/clueRef/sourceId").asText();
        mvc.perform(get("/api/v0/enforcement/work-items/{id}/source-links/{sourceId}", workId, sourceId)
                        .header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sourceId").value(sourceId))
                .andExpect(jsonPath("$.data.location").value("body"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.officialDecision").doesNotExist());
    }

    private JsonNode create(String key, String clueId) throws Exception {
        String body = mvc.perform(post("/api/v0/enforcement/work-items")
                        .header("X-V0-Actor", USER).header("X-V0-Organization", "org-v0")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"" + clueId + "\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private JsonNode process(String workId, String key, String evidenceId) throws Exception {
        String body = mvc.perform(post("/api/v0/enforcement/work-items/{id}/process", workId)
                        .header("X-V0-Actor", USER).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceIds\":[\"" + evidenceId + "\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
}
