package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevHumanContextContractScenarios {
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevHumanContextContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc; this.mapper = mapper; this.jdbc = jdbc;
    }

    void packageOnly() throws Exception {
        String owner = DevelopmentBoundaryPolicyTest.token("human-a");
        String receiver = DevelopmentBoundaryPolicyTest.token("human-b");
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", owner)
                .header("Idempotency-Key", "context-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "goal", "查找并比较用于温度调节的传感器、控制器和阀门方案"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("taskId").asText();
        String base = "/v2/consultation/tasks/" + task + "/human-requests";
        String path = base + "/1/context";
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", owner)
                .header("Idempotency-Key", "context-supplement-12345").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("expectedVersion", 1,
                        "message", "补充：该方案使用温度传感器、自动控制器和阀门。"))))
                .andExpect(status().isOk());
        String requestId = json(mvc.perform(post(base).header("Authorization", owner)
                .header("Idempotency-Key", "context-request-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":2,\"expectedRequestSequence\":0,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("requestId").asText();
        int receipts = count(task);
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        String body = "{\"expectedTaskRevision\":2,\"expectedRequestSequence\":1,\"requestId\":\""
                + requestId + "\"}";
        mvc.perform(post(base + "/1/accept").header("Authorization", receiver)
                .header("Idempotency-Key", "context-accept-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        receipts++;
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", receiver))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].kind").value("GOAL"))
                .andExpect(jsonPath("$.messages[1].kind").value("SUPPLEMENT"))
                .andExpect(jsonPath("$.requestText").isNotEmpty());
        assertThat(count(task)).isEqualTo(receipts);
        String digest = jdbc.queryForObject("SELECT body_sha FROM con2_message WHERE task_id=? AND seq=2",
                String.class, task);
        jdbc.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND seq=2", "0".repeat(64), task);
        try {
            mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
            assertThat(count(task)).isEqualTo(receipts);
        } finally {
            jdbc.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND seq=2", digest, task);
        }
        mvc.perform(post(base + "/1/return").header("Authorization", receiver)
                .header("Idempotency-Key", "context-return-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HUMAN_REQUEST_VERSION_CONFLICT"));
        assertThat(count(task)).isEqualTo(receipts + 1);
    }

    private int count(String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
    }
    private JsonNode json(String response) throws Exception { return mapper.readTree(response); }
}
