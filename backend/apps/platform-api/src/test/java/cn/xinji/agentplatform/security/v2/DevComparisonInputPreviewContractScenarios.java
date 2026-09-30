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

final class DevComparisonInputPreviewContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String SUPPLEMENT = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevComparisonInputPreviewContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void previewAndFailures() throws Exception {
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("taskId").asText();
        String path = "/v2/consultation/tasks/" + task;
        mvc.perform(post(path + "/messages").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message", SUPPLEMENT, "expectedVersion", 1))))
                .andExpect(status().isOk());
        String set = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedResultSequence\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("setId").asText();
        mvc.perform(post(path + "/focus").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("setId", set, "ordinal", 2,
                        "expectedTaskRevision", 2, "expectedFocusSequence", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordId").value("P2"));
        mvc.perform(post(path + "/feature-confirmations").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedFeatureRevision\":0,"
                        + "\"candidateManifestId\":\"DEV-CON-COMPARE-INPUT-01\","
                        + "\"featureIds\":[\"F1\",\"F2\",\"F3\"]}"))
                .andExpect(status().isOk());
        String preview = path + "/feature-confirmations/1/focus/1/input-preview";
        int receipts = count("con2_receipt", task), events = count("con2_event", task);
        mvc.perform(get(preview).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordId").value("P2"))
                .andExpect(jsonPath("$.setId").value(set)).andExpect(jsonPath("$.ordinal").value(2))
                .andExpect(jsonPath("$.targetText").value(SUPPLEMENT))
                .andExpect(jsonPath("$.features.length()").value(3))
                .andExpect(jsonPath("$.claimText").value("传感器、手动阀门"))
                .andExpect(jsonPath("$.analysisPurpose").value("NOT_SELECTED"))
                .andExpect(jsonPath("$.technicalRelation").value("NOT_ASSESSED"));
        mvc.perform(get(preview).header("Authorization", human())).andExpect(status().isOk());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        assertThat(count("con2_event", task)).isEqualTo(events);
        String second = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedResultSequence\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("setId").asText();
        mvc.perform(get(preview).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setId").value(set))
                .andExpect(jsonPath("$.recordId").value("P2"));
        mvc.perform(post(path + "/focus").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("setId", second, "ordinal", 1,
                        "expectedTaskRevision", 2, "expectedFocusSequence", 1))))
                .andExpect(status().isOk());
        mvc.perform(get(preview).header("Authorization", human())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FOCUS_NOT_BOUND"));
        String current = path + "/feature-confirmations/1/focus/2/input-preview";
        mvc.perform(get(current).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setId").value(second))
                .andExpect(jsonPath("$.ordinal").value(1));
        mvc.perform(get(path + "/feature-confirmations/2/focus/1/input-preview")
                .header("Authorization", human())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INPUT_NOT_BOUND"));
        mvc.perform(get(current).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get(current).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        String digest = jdbc.queryForObject("SELECT content_sha FROM con2_result_member "
                + "WHERE set_id=? AND ordinal=1", String.class, second);
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=1",
                "0".repeat(64), second);
        mvc.perform(get(current).header("Authorization", human())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=1", digest, second);
        mvc.perform(get(current).header("Authorization", human())).andExpect(status().isOk());
        mvc.perform(post(path + "/cancel").header("Authorization", human()).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get(current).header("Authorization", human())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CLOSED"));
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private JsonNode json(String result) throws Exception {
        return mapper.readTree(result);
    }

    private static String key() { return "preview-" + UUID.randomUUID(); }
    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
}
