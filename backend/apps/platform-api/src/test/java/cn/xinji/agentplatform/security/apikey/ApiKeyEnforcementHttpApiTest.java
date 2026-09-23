package cn.xinji.agentplatform.security.apikey;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "enforcement.storage=memory",
        "enforcement.source=synthetic",
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "api-access.enabled=true",
        "api-access.credentials[0].key-id=enforcement-key",
        "api-access.credentials[0].secret-sha256=3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9",
        "api-access.credentials[0].client-id=enforcement-client",
        "api-access.credentials[0].principal-ref=v0-enforcement-user",
        "api-access.credentials[0].organization-ref=org-v0",
        "api-access.credentials[0].status=ACTIVE",
        "api-access.credentials[0].expires-at=2099-01-01T00:00:00Z",
        "api-access.credentials[0].scopes=enforcement:work,enforcement:read,source:read",
        "api-access.credentials[0].requests-per-minute=100",
        "api-access.credentials[0].requests-per-day=1000",
        "api-access.credentials[1].key-id=reviewer-key",
        "api-access.credentials[1].secret-sha256=3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9",
        "api-access.credentials[1].client-id=reviewer-client",
        "api-access.credentials[1].principal-ref=v0-enforcement-reviewer",
        "api-access.credentials[1].organization-ref=org-v0",
        "api-access.credentials[1].status=ACTIVE",
        "api-access.credentials[1].expires-at=2099-01-01T00:00:00Z",
        "api-access.credentials[1].scopes=enforcement:review,enforcement:read",
        "api-access.credentials[1].requests-per-minute=100",
        "api-access.credentials[1].requests-per-day=1000"
})
@AutoConfigureMockMvc
class ApiKeyEnforcementHttpApiTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String USER_AUTH = "Bearer xj_enforcement-key." + SECRET;
    private static final String REVIEWER_AUTH = "Bearer xj_reviewer-key." + SECRET;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void requiresKeyAndUsesCredentialIdentityAndOrganization() throws Exception {
        mvc.perform(post("/api/v0/enforcement/work-items")
                        .header("Idempotency-Key", "api-enf-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REQUIRED"));

        String created = mvc.perform(post("/api/v0/enforcement/work-items")
                        .header("Authorization", USER_AUTH)
                        .header("X-V0-Actor", "spoofed-user")
                        .header("X-V0-Organization", "spoofed-org")
                        .header("X-Request-Id", "api-enforcement-1")
                        .header("Idempotency-Key", "api-enf-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "api-enforcement-1"))
                .andExpect(jsonPath("$.data.workItem.organizationRef").value("org-v0"))
                .andReturn().getResponse().getContentAsString();

        String workId = mapper.readTree(created).at("/data/workItem/workItemId").asText();
        mvc.perform(get("/api/v0/enforcement/work-items/{id}", workId)
                        .header("Authorization", USER_AUTH))
                .andExpect(status().isOk());
    }

    @Test void separatesWorkAndHumanReviewScopes() throws Exception {
        String created = mvc.perform(post("/api/v0/enforcement/work-items")
                        .header("Authorization", USER_AUTH)
                        .header("Idempotency-Key", "api-enf-review-open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clueId\":\"V0-SYN-CLUE-001\",\"clueVersion\":\"v1\"}"))
                .andReturn().getResponse().getContentAsString();
        String workId = mapper.readTree(created).at("/data/workItem/workItemId").asText();
        String processed = mvc.perform(post("/api/v0/enforcement/work-items/{id}/process", workId)
                        .header("Authorization", USER_AUTH)
                        .header("Idempotency-Key", "api-enf-review-process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceIds\":[\"V0-SYN-EVD-001\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String analysisId = mapper.readTree(processed).at("/data/auxiliaryAnalyses/0/analysisResultId").asText();
        String path = "/api/v0/enforcement/work-items/{id}/auxiliary-analyses/{analysisId}/actions";
        String body = "{\"action\":\"CONFIRM\",\"expectedVersion\":1,\"reason\":\"scope check\"}";

        mvc.perform(post(path, workId, analysisId)
                        .header("Authorization", USER_AUTH)
                        .header("Idempotency-Key", "api-enf-review-denied")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

        mvc.perform(post(path, workId, analysisId)
                        .header("Authorization", REVIEWER_AUTH)
                        .header("Idempotency-Key", "api-enf-review-ok")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultType").value("REVIEW_RECORDED"))
                .andExpect(jsonPath("$.data.reviews[0].actorRef").value("v0-enforcement-reviewer"));
    }
}
