package cn.xinji.agentplatform.consultation.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"patent.fixture.enabled=true", "consultation.storage=memory",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"})
@ActiveProfiles("test-synthetic")
@AutoConfigureMockMvc
class PatentFixtureFlowHttpTest {
    private static final String ROOT = "/api/v0/consultations/patent-fixture";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void localJourneyChecksSourceVersionsAndCorrection() throws Exception {
        JsonNode started = create("journey", """
                {"description":"样例技术方案","scope":"fixture-scope","features":["测温单元"]}
                """);
        String id = started.get("taskId").asText();
        JsonNode found = action(id, "/searches", "search", """
                {"expectedTaskVersion":%d,"query":"测温"}
                """.formatted(started.get("taskVersion").asLong()));
        assertThat(found.get("candidates").size()).isEqualTo(2);
        JsonNode first = found.get("candidates").get(0);
        mvc.perform(get(ROOT + "/" + id + "/source-links/" + first.get("candidateId").asText())
                        .param("version", first.get("documentVersion").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locator").isNotEmpty());
        JsonNode selected = action(id, "/selections", "select", """
                {"expectedTaskVersion":%d,"resultVersion":1,"ordinal":1}
                """.formatted(found.get("taskVersion").asLong()));
        assertThat(selected.get("selectedCandidateReference").asText()).isEqualTo("1:" + first.get("candidateId").asText());
        JsonNode comparison = action(id, "/comparisons", "compare", """
                {"expectedTaskVersion":%d}
                """.formatted(selected.get("taskVersion").asLong()));
        assertThat(comparison.get("comparisons").get(0).get("relationship").asText()).isEqualTo("UNKNOWN");
        JsonNode corrected = action(id, "/corrections/features/feature-1", "correct", """
                {"expectedTaskVersion":%d,"replacement":"多点测温"}
                """.formatted(comparison.get("taskVersion").asLong()));
        assertThat(corrected.get("candidates").get(0).get("available").asBoolean()).isFalse();
        mvc.perform(get(ROOT + "/" + id + "/source-links/" + first.get("candidateId").asText())
                        .param("version", first.get("documentVersion").asText()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test void idempotencyStaleVersionAndRemoteAccessAreControlled() throws Exception {
        String body = """
                {"description":"测试方案","scope":"fixture-scope","features":["技术特征"]}
                """;
        JsonNode created = create("repeat", body);
        assertThat(create("repeat", body).get("taskId").asText()).isEqualTo(created.get("taskId").asText());
        mvc.perform(post(ROOT).header("Idempotency-Key", "repeat").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"另一方案","scope":"fixture-scope","features":["技术特征"]}
                                """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYLOAD_CONFLICT"));
        String id = created.get("taskId").asText();
        JsonNode found = action(id, "/searches", "same-search", """
                {"expectedTaskVersion":%d,"query":"检索"}
                """.formatted(created.get("taskVersion").asLong()));
        assertThat(action(id, "/searches", "same-search", """
                {"expectedTaskVersion":%d,"query":"检索"}
                """.formatted(created.get("taskVersion").asLong())).get("taskVersion").asLong())
                .isEqualTo(found.get("taskVersion").asLong());
        mvc.perform(post(ROOT + "/" + id + "/comparisons").header("Idempotency-Key", "stale")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"expectedTaskVersion":%d}
                                """.formatted(created.get("taskVersion").asLong())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(get(ROOT + "/" + id).with(request -> { request.setRemoteAddr("203.0.113.1"); return request; }))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ONLY"));
    }

    private JsonNode create(String key, String body) throws Exception {
        return mapper.readTree(mvc.perform(post(ROOT).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode action(String id, String path, String key, String body) throws Exception {
        return mapper.readTree(mvc.perform(post(ROOT + "/" + id + path).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
