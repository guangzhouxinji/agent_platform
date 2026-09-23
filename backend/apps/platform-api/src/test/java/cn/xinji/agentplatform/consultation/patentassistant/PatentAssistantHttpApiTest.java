package cn.xinji.agentplatform.consultation.patentassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "consultation.storage=memory",
        "patent-assistant.source.provider=public-static",
        "patent-assistant.model.provider=disabled",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class PatentAssistantHttpApiTest {
    @Autowired MockMvc mvc;

    @Test
    void exposesPublicDataAndModelReadiness() throws Exception {
        mvc.perform(get("/api/pilot/patent-assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataMode").value("PUBLIC_PILOT"))
                .andExpect(jsonPath("$.sourceCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.modelConfigured").value(false));
    }

    @Test
    void reportsMissingDeepSeekKeyInsteadOfReturningFakeAnalysis() throws Exception {
        mvc.perform(post("/api/pilot/patent-assistant/analyze")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"基于知识图谱进行专利检索并重排候选\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
    }

    @Test
    void searchesWithoutCallingDeepSeek() throws Exception {
        mvc.perform(post("/api/pilot/patent-assistant/search")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"基于知识图谱进行专利检索并重排候选\",\"page\":1,\"pageSize\":5,\"country\":\"CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("public-static"))
                .andExpect(jsonPath("$.sources[0].citation").value("P1"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(5))
                .andExpect(jsonPath("$.appliedCountry").value("CN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsUnsupportedPagingAndCountryBeforeSearching() throws Exception {
        mvc.perform(post("/api/pilot/patent-assistant/search")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"基于知识图谱进行专利检索并重排候选\",\"page\":0,\"pageSize\":7,\"country\":\"JP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnauthorizedRequester() throws Exception {
        mvc.perform(post("/api/pilot/patent-assistant/analyze")
                        .header("X-V0-Requester", "unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"水性粘结剂双区烘干涂布工艺\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
