package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevCurrentWorkStateContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevCurrentWorkStateContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void restoreAndReject() throws Exception {
        String task = mapper.readTree(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", "state-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("taskId").asText();
        String path = "/v2/consultation/tasks/" + task;
        String state = path + "/current-work-state";
        mvc.perform(get(state).header("Authorization", human())).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultSequence").value(0))
                .andExpect(jsonPath("$.focusSequence").value(0))
                .andExpect(jsonPath("$.featureRevision").value(0))
                .andExpect(jsonPath("$.resultSet").isEmpty());
        mvc.perform(get(state).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-b")))
                .andExpect(status().isNotFound());
        mvc.perform(get(state).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        mvc.perform(post(state).header("Authorization", human())).andExpect(status().isNotFound());

        JsonNode first = response(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "state-search-1-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}"));
        String firstSet = first.path("setId").asText();
        mvc.perform(post(path + "/focus").header("Authorization", human())
                .header("Idempotency-Key", "state-focus-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setId\":\"" + firstSet + "\",\"ordinal\":2,"
                        + "\"expectedTaskRevision\":1,\"expectedFocusSequence\":0}"))
                .andExpect(status().isOk());
        JsonNode second = response(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "state-search-2-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":1}"));
        mvc.perform(post(path + "/messages").header("Authorization", human())
                .header("Idempotency-Key", "state-append-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/feature-confirmations").header("Authorization", human())
                .header("Idempotency-Key", "state-feature-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedFeatureRevision\":0,"
                        + "\"candidateManifestId\":\"DEV-CON-COMPARE-INPUT-01\","
                        + "\"featureIds\":[\"F1\",\"F2\",\"F3\"]}"))
                .andExpect(status().isOk());
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
        int events = jdbc.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?", Integer.class, task);
        Object expiry = jdbc.queryForObject("SELECT expires_at FROM con2_task WHERE task_id=?", Object.class, task);
        String content = mvc.perform(get(state).header("Authorization", human())).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskRevision").value(2))
                .andExpect(jsonPath("$.resultSequence").value(2))
                .andExpect(jsonPath("$.resultSet.setId").value(second.path("setId").asText()))
                .andExpect(jsonPath("$.resultSet.currentInput").value(false))
                .andExpect(jsonPath("$.focus.setId").value(firstSet))
                .andExpect(jsonPath("$.focus.ordinal").value(2))
                .andExpect(jsonPath("$.focus.currentInput").value(false))
                .andExpect(jsonPath("$.feature.manifestId").value("DEV-CON-COMPARE-INPUT-01"))
                .andExpect(jsonPath("$.feature.currentInput").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(content).doesNotContain(GOAL, MESSAGE, "claimText", "contentDigest");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?", Integer.class, task))
                .isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM con2_task WHERE task_id=?", Object.class, task))
                .isEqualTo(expiry);
        jdbc.update("UPDATE con2_result_state SET result_seq=999 WHERE task_id=?", task);
        mvc.perform(get(state).header("Authorization", human())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_STATE_INCONSISTENT"));
        jdbc.update("UPDATE con2_result_state SET result_seq=2 WHERE task_id=?", task);
        mvc.perform(post(path + "/cancel").header("Authorization", human())
                .header("Idempotency-Key", "state-cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get(state).header("Authorization", human())).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskState").value("CANCELLED"))
                .andExpect(jsonPath("$.feature.currentInput").value(false));
        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(get(state).header("Authorization", human())).andExpect(status().isGone());
    }

    private JsonNode response(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
}
