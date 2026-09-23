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
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "api-access.enabled=true",
        "api-access.credentials[0].key-id=test-key",
        "api-access.credentials[0].secret-sha256=3eb1bd439947eb762998e566ccc2e099c791118b2f40579cc4f7da2b5061b7f9",
        "api-access.credentials[0].client-id=test-client",
        "api-access.credentials[0].principal-ref=v0-authorized-user",
        "api-access.credentials[0].status=ACTIVE",
        "api-access.credentials[0].expires-at=2099-01-01T00:00:00Z",
        "api-access.credentials[0].scopes=consultation:submit,consultation:read,source:read",
        "api-access.credentials[0].requests-per-minute=100",
        "api-access.credentials[0].requests-per-day=1000"
})
@AutoConfigureMockMvc
class ApiKeyConsultationHttpApiTest {
    private static final String AUTHORIZATION = "Bearer xj_test-key.0123456789abcdef0123456789abcdef";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void rejectsMissingAndInvalidCredentials() throws Exception {
        mvc.perform(post("/api/v0/consultations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"水性粘结剂 双区烘干\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("API_KEY_REQUIRED"))
                .andExpect(header().exists("X-Request-Id"));
        mvc.perform(post("/api/v0/consultations").header("Authorization", "Bearer xj_test-key.wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"水性粘结剂 双区烘干\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test void keyIdentityOverridesSpoofedRequesterAndAllowsSourceRead() throws Exception {
        String response = mvc.perform(post("/api/v0/consultations")
                        .header("Authorization", AUTHORIZATION)
                        .header("X-V0-Requester", "spoofed-user")
                        .header("X-Request-Id", "external-request-1")
                        .header("Idempotency-Key", "api-key-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"水性粘结剂 双区烘干\",\"purpose\":\"TECHNICAL_REFERENCE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "external-request-1"))
                .andExpect(jsonPath("$.outcomeType").value("ANSWER_READY"))
                .andExpect(jsonPath("$.sourceLinks").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        var body = mapper.readTree(response);
        String consultationId = body.path("consultationId").asText();
        String sourceLinkId = body.path("sourceLinks").path(0).path("sourceLinkId").asText();
        mvc.perform(get("/api/v0/consultations/{id}", consultationId).header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consultationId").value(consultationId));
        mvc.perform(get("/api/v0/consultations/{id}/source-links/{source}", consultationId, sourceLinkId)
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceId").isNotEmpty());
    }
}
