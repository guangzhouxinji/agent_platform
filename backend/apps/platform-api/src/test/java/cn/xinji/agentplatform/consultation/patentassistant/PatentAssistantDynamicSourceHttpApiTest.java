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
        "patent-assistant.source.provider=surechembl",
        "patent-assistant.model.provider=disabled",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class PatentAssistantDynamicSourceHttpApiTest {
    @Autowired MockMvc mvc;

    @Test
    void exposesDynamicSourceBoundary() throws Exception {
        mvc.perform(get("/api/pilot/patent-assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceProvider").value("public-static"))
                .andExpect(jsonPath("$.dataMode").value("PUBLIC_PILOT"))
                .andExpect(jsonPath("$.externalModelUseAllowed").value(true))
                .andExpect(jsonPath("$.searchSourceProvider").value("surechembl"))
                .andExpect(jsonPath("$.searchDataMode").value("PUBLIC_DYNAMIC"))
                .andExpect(jsonPath("$.searchExternalModelUseAllowed").value(false));
    }

    @Test
    void analysisUsesApprovedStaticSourceAndReportsMissingModel() throws Exception {
        mvc.perform(post("/api/pilot/patent-assistant/analyze")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"基于知识图谱进行专利检索并重排候选\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
    }
}
