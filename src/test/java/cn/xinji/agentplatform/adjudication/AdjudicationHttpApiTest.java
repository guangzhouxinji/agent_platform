package cn.xinji.agentplatform.adjudication;

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
        "adjudication.storage=memory",
        "enforcement.storage=memory",
        "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
class AdjudicationHttpApiTest {
    private static final String USER = "v0-adjudication-user";
    private static final String REVIEWER = "v0-adjudication-reviewer";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void completesNineRouteSyntheticFlowAndReadsExactDraftVersion() throws Exception {
        String workId = open("http-open").at("/data/work/workItemId").asText();
        inspect(workId, "http-inspect");
        postJson("/api/v0/adjudication/work-items/" + workId + "/legal-candidate-sets", USER, "http-match",
                "{\"inspectionVersion\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("LEGAL_CANDIDATES_READY"));
        postJson("/api/v0/adjudication/work-items/" + workId + "/citations/actions", REVIEWER, "http-cite",
                "{\"expectedVersion\":0,\"code\":\"V0-SYN-ADJ-LAW-001\",\"action\":\"SELECT\",\"reason\":\"synthetic selection\"}")
                .andExpect(status().isOk());
        JsonNode drafted = response(postJson("/api/v0/adjudication/work-items/" + workId + "/drafts", USER, "http-draft",
                "{\"inspectionVersion\":1}").andExpect(status().isOk()));
        String draftId = drafted.at("/data/drafts/0/draftId").asText();
        postJson("/api/v0/adjudication/work-items/" + workId + "/drafts/actions", REVIEWER, "http-revise",
                "{\"expectedVersion\":1,\"replacementText\":\"人工合成修订\",\"reason\":\"synthetic revision\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.drafts.length()").value(2));
        postJson("/api/v0/adjudication/work-items/" + workId + "/drafts/test-reviews", REVIEWER, "http-review",
                "{\"expectedDraftVersion\":2,\"action\":\"CONFIRM\",\"reason\":\"synthetic review\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviews[0].notOfficialDecision").value(true))
                .andExpect(jsonPath("$.limitations[0]").value("SYNTHETIC_V0"))
                .andExpect(jsonPath("$.limitations[1]").value("NOT_OFFICIAL_DECISION"));
        mvc.perform(get("/api/v0/adjudication/work-items/{id}", workId).header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.drafts.length()").value(2));
        mvc.perform(get("/api/v0/adjudication/work-items/{id}/drafts/{draftId}/versions/1", workId, draftId)
                        .header("X-V0-Actor", USER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.sections[0].content").value("合成草稿：待核实事项和来源映射已保留。"));
    }

    @Test
    void keepsAuthorizationIdempotencyAndVersionErrorsStable() throws Exception {
        mvc.perform(post("/api/v0/adjudication/work-items")
                        .header("X-V0-Organization", "org-v0").header("Idempotency-Key", "missing-actor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        String workId = open("conflict-key").at("/data/work/workItemId").asText();
        mvc.perform(post("/api/v0/adjudication/work-items")
                        .header("X-V0-Actor", USER).header("X-V0-Organization", "other-org")
                        .header("Idempotency-Key", "conflict-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        inspect(workId, "inspect-version");
        postJson("/api/v0/adjudication/work-items/" + workId + "/legal-candidate-sets", USER, "stale-version",
                "{\"inspectionVersion\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void blocksConfirmationWhenSyntheticMaterialsAreIncomplete() throws Exception {
        JsonNode opened = response(mvc.perform(post("/api/v0/adjudication/work-items")
                .header("X-V0-Actor", USER).header("X-V0-Organization", "org-v0")
                .header("Idempotency-Key", "open-incomplete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-002\"]}"))
                .andExpect(status().isOk()));
        String workId = opened.at("/data/work/workItemId").asText();
        inspect(workId, "inspect-incomplete");
        postJson("/api/v0/adjudication/work-items/" + workId + "/drafts", USER, "draft-incomplete",
                "{\"inspectionVersion\":1}").andExpect(status().isOk());
        postJson("/api/v0/adjudication/work-items/" + workId + "/drafts/test-reviews", REVIEWER, "review-incomplete",
                "{\"expectedDraftVersion\":1,\"action\":\"CONFIRM\",\"reason\":\"should fail\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MATERIALS_INSUFFICIENT"));
    }

    private JsonNode open(String key) throws Exception {
        return response(mvc.perform(post("/api/v0/adjudication/work-items")
                .header("X-V0-Actor", USER).header("X-V0-Organization", "org-v0")
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseId\":\"V0-SYN-ADJ-CASE-001\",\"caseVersion\":\"v1\",\"materialIds\":[\"V0-SYN-ADJ-MAT-001\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.limitations[1]").value("NOT_OFFICIAL_DECISION")));
    }

    private void inspect(String workId, String key) throws Exception {
        mvc.perform(post("/api/v0/adjudication/work-items/{id}/material-inspections", workId)
                        .header("X-V0-Actor", USER).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultType").value("MATERIAL_INSPECTION_READY"));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String actor, String key, String body) throws Exception {
        return mvc.perform(post(path).header("X-V0-Actor", actor).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode response(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsString());
    }
}
