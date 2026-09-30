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

final class DevCorrectionContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String OLD = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private static final String NEW = "补充：该方案使用温度传感器、自动控制器和电磁阀。";
    private static final String UTTERANCE = "纠正：补充里的阀门应为电磁阀，其他不变。";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevCorrectionContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc; this.mapper = mapper; this.jdbc = jdbc;
    }

    void correctAndContinue() throws Exception {
        String owner = DevelopmentBoundaryPolicyTest.token("human-a");
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", owner)
                .header("Idempotency-Key", "correct-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("taskId").asText();
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", owner)
                .header("Idempotency-Key", "correct-append-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message", OLD, "expectedVersion", 1))))
                .andExpect(status().isOk());
        String path = "/v2/consultation/tasks/" + task + "/corrections";
        String body = mapper.writeValueAsString(Map.of("expectedVersion", 2, "sourceRevision", 2,
                "newText", NEW, "userMessage", UTTERANCE));
        int before = count(task);
        mvc.perform(post(path).header("Authorization", DevelopmentBoundaryPolicyTest.token("human-b"))
                .header("Idempotency-Key", "correct-other-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent"))
                .header("Idempotency-Key", "correct-agent-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        assertThat(count(task)).isEqualTo(before);
        String key = "correct-save-" + UUID.randomUUID();
        JsonNode saved = json(mvc.perform(post(path).header("Authorization", owner)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(saved.path("revision").asLong()).isEqualTo(3);
        mvc.perform(post(path).header("Authorization", owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(saved.path("receiptId").asText()));
        mvc.perform(post(path).header("Authorization", owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body.replace(NEW, OLD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(path).header("Authorization", owner).header("Idempotency-Key", "correct-again-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[1].body").value(OLD))
                .andExpect(jsonPath("$.messages[2].body").value(NEW))
                .andExpect(jsonPath("$.corrections[0].utterance").value(UTTERANCE));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_correction WHERE task_id=?",
                Integer.class, task)).isEqualTo(1);
        assertThat(count(task)).isEqualTo(before + 1);
        String manifestSha = jdbc.queryForObject("SELECT manifest_sha FROM con2_correction WHERE task_id=?",
                String.class, task);
        jdbc.update("UPDATE con2_correction SET manifest_sha=? WHERE task_id=?", "0".repeat(64), task);
        String denied = mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", owner))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(denied).doesNotContain(NEW, UTTERANCE);
        jdbc.update("UPDATE con2_correction SET manifest_sha=? WHERE task_id=?", manifestSha, task);
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", owner)
                .header("Idempotency-Key", "correct-stale-12345").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message", OLD, "expectedVersion", 2))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", owner)
                .header("Idempotency-Key", "correct-cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}")).andExpect(status().isOk());
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.corrections[0].utterance").value(UTTERANCE));
        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.ofInstant(java.time.Instant.now().minusSeconds(1),
                        java.time.ZoneOffset.UTC)), task);
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", owner))
                .andExpect(status().isGone());
        mvc.perform(post("/v2/internal/consultation/retention/reconcile")
                .header("Authorization", DevelopmentBoundaryPolicyTest.token("job")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_correction WHERE task_id=?",
                Integer.class, task)).isZero();
    }

    private int count(String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
    }
    private JsonNode json(String body) throws Exception { return mapper.readTree(body); }
}
