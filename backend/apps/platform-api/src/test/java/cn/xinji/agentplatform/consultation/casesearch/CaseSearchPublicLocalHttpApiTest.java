package cn.xinji.agentplatform.consultation.casesearch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "knowledge-base.provider=classpath",
        "consultation.search.provider=public-local",
        "patent-search.catalog=public-local",
        "consultation.storage=memory",
        "case-search.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@ActiveProfiles("public-local-test")
@AutoConfigureMockMvc
class CaseSearchPublicLocalHttpApiTest {
    @Autowired MockMvc mvc;

    @Test void searchesThePublicLocalCaseSnapshotWithLocatedSourceText() throws Exception {
        mvc.perform(post("/api/v0/case-search-tasks")
                        .header("X-V0-Requester", "v0-authorized-user")
                        .header("Idempotency-Key", "public-local-case-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"专利侵权案例","confirmedKeywords":["专利","侵权"],
                                 "caseTypes":[],"intellectualPropertyTypes":[],"institutions":[],"procedures":[],
                                 "decisionFrom":null,"decisionTo":null,"resultLimit":10}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.dataMode").value("PUBLIC_LOCAL"))
                .andExpect(jsonPath("$.sourceVersion").value("public-local-case-v1"))
                .andExpect(jsonPath("$.totalCandidates").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.candidates[0].sourceRef").isNotEmpty())
                .andExpect(jsonPath("$.candidates[0].evidence.state").value("SOURCE_TEXT"))
                .andExpect(jsonPath("$.candidates[0].evidence.sourceLocation").isNotEmpty());
    }
}
