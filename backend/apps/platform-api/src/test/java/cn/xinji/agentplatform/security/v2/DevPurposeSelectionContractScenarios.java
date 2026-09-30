package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevPurposeSelectionContractScenarios {
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevPurposeSelectionContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void selectionAndFailures() throws Exception {
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("goal",
                        "查找并比较用于温度调节的传感器、控制器和阀门方案"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("taskId").asText();
        String path = "/v2/consultation/tasks/" + task;
        mvc.perform(post(path + "/messages").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "message", "补充：该方案使用温度传感器、自动控制器和阀门。", "expectedVersion", 1))))
                .andExpect(status().isOk());
        String set = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedResultSequence\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("setId").asText();
        mvc.perform(post(path + "/focus").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "setId", set, "ordinal", 2, "expectedTaskRevision", 2, "expectedFocusSequence", 0))))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/feature-confirmations").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedFeatureRevision\":0,"
                        + "\"candidateManifestId\":\"DEV-CON-COMPARE-INPUT-01\","
                        + "\"featureIds\":[\"F1\",\"F2\",\"F3\"]}"))
                .andExpect(status().isOk());
        jdbc.update("DELETE FROM con2_lease WHERE task_id=?", task);
        String endpoint = path + "/analysis-purposes";
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisPurpose").value("NOT_SELECTED"));
        String body = mapper.writeValueAsString(Map.of("expectedTaskRevision", 2, "featureRevision", 1,
                "focusSequence", 1, "setId", set, "ordinal", 2,
                "analysisPurpose", "TECHNICAL_SIMILARITY"));
        String idem = key();
        int before = count("con2_receipt", task), events = count("con2_event", task);
        String digest = jdbc.queryForObject("SELECT content_sha FROM con2_result_member "
                + "WHERE set_id=? AND ordinal=2", String.class, set);
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2",
                "0".repeat(64), set);
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        assertThat(count("con2_receipt", task)).isEqualTo(before);
        assertThat(count("con2_event", task)).isEqualTo(events);
        assertThat(count("con2_purpose_revision", task)).isZero();
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2", digest, set);
        mvc.perform(post(endpoint).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent"))
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(body.replace(set, UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        assertThat(count("con2_receipt", task)).isEqualTo(before);
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", idem)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisPurpose").value("TECHNICAL_SIMILARITY"))
                .andExpect(jsonPath("$.purposeSequence").value(1)).andExpect(jsonPath("$.currentInput").value(true));
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", idem)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purposeSequence").value(1));
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURPOSE_ALREADY_SELECTED"));
        assertThat(count("con2_purpose_revision", task)).isEqualTo(1);
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purposeSequence").value(1));
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2",
                "0".repeat(64), set);
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        assertThat(count("con2_purpose_revision", task)).isEqualTo(1);
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2", digest, set);
        mvc.perform(get(endpoint + "/1").header("Authorization", DevelopmentBoundaryPolicyTest.token("human-b")))
                .andExpect(status().isNotFound());
        mvc.perform(post(path + "/focus").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "setId", set, "ordinal", 1, "expectedTaskRevision", 2, "expectedFocusSequence", 1))))
                .andExpect(status().isOk());
        mvc.perform(get(endpoint + "/1").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentInput").value(false));
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.analysisPurpose").value("NOT_SELECTED"));
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        String next = mapper.writeValueAsString(Map.of("expectedTaskRevision", 2, "featureRevision", 1,
                "focusSequence", 2, "setId", set, "ordinal", 1,
                "analysisPurpose", "TECHNICAL_SIMILARITY"));
        mvc.perform(post(endpoint).header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(next))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purposeSequence").value(2));
        mvc.perform(post(path + "/cancel").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get(endpoint + "/current").header("Authorization", human()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLOSED"));
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private JsonNode json(String body) throws Exception { return mapper.readTree(body); }
    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
    private static String key() { return "purpose-" + UUID.randomUUID(); }
}
