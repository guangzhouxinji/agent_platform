package cn.xinji.agentplatform.consultation.guidance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"consultation.storage=memory", "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@AutoConfigureMockMvc
class ConsultationGuidanceHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void returnsOrderedDevelopmentDraftsWithExplicitVersionAndLimit() throws Exception {
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "v0-authorized-user").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("LOCAL-GUIDANCE-V1"))
                .andExpect(jsonPath("$.status").value("DEVELOPMENT_DRAFT"))
                .andExpect(jsonPath("$.guides.length()").value(2))
                .andExpect(jsonPath("$.guides[0].guideId").value("LG-PATENT-INFO-01"))
                .andExpect(jsonPath("$.guides[0].relatedQuestionIds[0]").value("PATENT_INFORMATION"))
                .andExpect(jsonPath("$.limitation").value(org.hamcrest.Matchers.containsString("不自动执行")));
    }

    @Test void filtersByKnownScenarioWithoutExpandingTheRequestedScope() throws Exception {
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "v0-authorized-user")
                        .param("scenarioId", "CASE_REFERENCE").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioId").value("CASE_REFERENCE"))
                .andExpect(jsonPath("$.guides.length()").value(1))
                .andExpect(jsonPath("$.guides[0].scenarioId").value("CASE_REFERENCE"));
    }

    @Test void rejectsUnknownScopeUnsupportedLanguageAndInvalidLimit() throws Exception {
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "v0-authorized-user")
                        .param("scenarioId", "LAW_PROCESS_CONSULTATION"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SCENARIO"));
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "v0-authorized-user")
                        .param("language", "en-US"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_LANGUAGE"));
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "v0-authorized-user")
                        .param("limit", "11"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
    }

    @Test void rejectsUnauthorizedRequester() throws Exception {
        mvc.perform(get("/api/v0/consultation-guides").header("X-V0-Requester", "unknown"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
