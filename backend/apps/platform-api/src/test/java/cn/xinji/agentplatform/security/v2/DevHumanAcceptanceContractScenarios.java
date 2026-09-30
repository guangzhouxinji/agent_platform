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

final class DevHumanAcceptanceContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevHumanAcceptanceContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void acceptanceAndFailures() throws Exception {
        String a = DevelopmentBoundaryPolicyTest.token("human-a");
        String b = DevelopmentBoundaryPolicyTest.token("human-b");
        String task = mapper.readTree(mvc.perform(post("/v2/consultation/tasks").header("Authorization", a)
                .header("Idempotency-Key", "accept-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        java.util.Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("taskId").asText();
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        String queue = "/v2/consultation/human-requests/pending";
        mvc.perform(get(queue).header("Authorization", b)).andExpect(status().isOk());
        JsonNode requested = mapper.readTree(mvc.perform(post(route).header("Authorization", a)
                .header("Idempotency-Key", "accept-request-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":0,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String id = requested.path("requestId").asText();
        JsonNode pending = mapper.readTree(mvc.perform(get(queue).header("Authorization", b))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(pending.toString()).contains(id).doesNotContain(GOAL, "请人工核对");
        assertThat(pending.findValuesAsText("taskId")).contains(task);
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", b))
                .andExpect(status().isNotFound());
        for (String actor : new String[] {"agent", "job"}) {
            mvc.perform(get(queue).header("Authorization", DevelopmentBoundaryPolicyTest.token(actor)))
                    .andExpect(status().isForbidden());
        }
        int receipts = count("con2_receipt", task);
        String path = route + "/1/accept";
        String body = "{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,\"requestId\":\"" + id + "\"}";
        String unrelated = mapper.readTree(mvc.perform(post("/v2/consultation/tasks")
                .header("Authorization", a).header("Idempotency-Key", "accept-other-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                        java.util.Map.of("goal", GOAL)))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("taskId").asText();
        mvc.perform(post("/v2/consultation/tasks/{id}/human-requests/1/accept", unrelated)
                .header("Authorization", b).header("Idempotency-Key", "accept-cross-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(count("con2_receipt", unrelated)).isEqualTo(1);
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-invalid-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace(id, UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-decimal-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"expectedTaskRevision\":1", "\"expectedTaskRevision\":1.5")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        JsonNode accepted = mapper.readTree(mvc.perform(post(path).header("Authorization", b)
                .header("Idempotency-Key", "accept-valid-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.acceptedBy").value("dev-con-b"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(accepted.toString()).doesNotContain(GOAL, "请人工核对");
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-valid-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedAt").value(accepted.path("acceptedAt").asText()));
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-valid-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"expectedTaskRevision\":1", "\"expectedTaskRevision\":2")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(path).header("Authorization", a).header("Idempotency-Key", "accept-second-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get(route + "/current").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.acceptanceState").value("ACCEPTED"))
                .andExpect(jsonPath("$.request.acceptedBy").value("dev-con-b"));
        assertThat(mapper.readTree(mvc.perform(get(queue).header("Authorization", b))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).toString())
                .doesNotContain(id);
        assertThat(count("con2_human_acceptance", task)).isEqualTo(1);
        assertThat(count("con2_receipt", task)).isEqualTo(receipts + 1);
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", a)
                .header("Idempotency-Key", "accept-supplement-12345")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "message", "补充：该方案使用温度传感器、自动控制器和阀门。", "expectedVersion", 1))))
                .andExpect(status().isOk());
        mvc.perform(get(route + "/1").header("Authorization", a)).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceState").value("ACCEPTED"))
                .andExpect(jsonPath("$.currentInput").value(false));
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-valid-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", a)
                .header("Idempotency-Key", "accept-cancel-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get(route + "/1").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptanceState").value("ACCEPTED"))
                .andExpect(jsonPath("$.currentInput").value(false));
        mvc.perform(post(path).header("Authorization", b).header("Idempotency-Key", "accept-valid-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }
}
