package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevHumanRequestContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private static final String BODY = "{\"expectedTaskRevision\":1,\"expectedRequestSequence\":0,"
            + "\"requestInputId\":\"REQUEST_GAP\"}";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevHumanRequestContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void requestHistoryAndFailures() throws Exception {
        String task = mapper.readTree(mvc.perform(post("/v2/consultation/tasks")
                .header("Authorization", human()).header("Idempotency-Key", "human-task-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        java.util.Map.of("goal", GOAL)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("taskId").asText();
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        mvc.perform(get(route + "/current").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestSequence").value(0))
                .andExpect(jsonPath("$.request").value((Object) null));
        int receipts = count("con2_receipt", task);
        int events = count("con2_event", task);
        for (String invalid : new String[] {
                BODY.replace("REQUEST_GAP", "UNKNOWN"),
                BODY.replace("\"expectedTaskRevision\":1", "\"expectedTaskRevision\":1.5"),
                BODY.replace("\"expectedRequestSequence\":0", "\"expectedRequestSequence\":0.5"),
                BODY.substring(0, BODY.length() - 1) + ",\"receiver\":\"dev-con-b\"}"
        }) {
            mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", "invalid-123456")
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().is4xxClientError());
        }
        mvc.perform(post(route).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-b"))
                .header("Idempotency-Key", "other-human-123456")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNotFound());
        for (String credential : new String[] {"agent", "job"}) {
            mvc.perform(post(route).header("Authorization", DevelopmentBoundaryPolicyTest.token(credential))
                    .header("Idempotency-Key", "service-denied-123456")
                    .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        assertThat(count("con2_event", task)).isEqualTo(events);

        String key = "human-request-" + UUID.randomUUID();
        JsonNode first = mapper.readTree(mvc.perform(post(route).header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestSequence").value(1))
                .andExpect(jsonPath("$.acceptanceState").value("NOT_ACCEPTED"))
                .andExpect(jsonPath("$.currentInput").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.path("requestText").asText()).isEqualTo("请人工核对当前分析目的与证据缺口。");
        mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(first.path("requestId").asText()));
        mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"expectedRequestSequence\":0", "\"expectedRequestSequence\":1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", "stale-123456")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        assertThat(count("con2_human_request", task)).isEqualTo(1);
        assertThat(count("con2_receipt", task)).isEqualTo(receipts + 1);
        assertThat(count("con2_event", task)).isEqualTo(events + 1);

        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "human-supplement-123456")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        java.util.Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isOk());
        mvc.perform(get(route + "/1").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentInput").value(false))
                .andExpect(jsonPath("$.acceptanceState").value("NOT_ACCEPTED"));
        String next = BODY.replace("\"expectedTaskRevision\":1", "\"expectedTaskRevision\":2")
                .replace("\"expectedRequestSequence\":0", "\"expectedRequestSequence\":1");
        mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", "new-input-123456")
                .contentType(MediaType.APPLICATION_JSON).content(next)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestSequence").value(2));
        mvc.perform(get(route + "/current").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.currentInput").value(true));
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", human())
                .header("Idempotency-Key", "human-cancel-123456")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(get(route + "/2").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentInput").value(false))
                .andExpect(jsonPath("$.taskState").value("CANCELLED"));
        mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", "after-cancel-123456")
                .contentType(MediaType.APPLICATION_JSON).content(next)).andExpect(status().isConflict());
        assertThat(count("con2_human_request", task)).isEqualTo(2);

        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(get(route + "/1").header("Authorization", human())).andExpect(status().isGone());
        mvc.perform(post("/v2/internal/consultation/retention/reconcile")
                .header("Authorization", DevelopmentBoundaryPolicyTest.token("job")))
                .andExpect(status().isOk());
        assertThat(count("con2_human_request", task)).isZero();
        assertThat(count("con2_task", task)).isZero();
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }

    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
}
