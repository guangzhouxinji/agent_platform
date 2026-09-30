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

final class DevHumanOpinionContractScenarios {
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevHumanOpinionContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc; this.mapper = mapper; this.jdbc = jdbc;
    }

    void opinionAndHistory() throws Exception {
        String owner = DevelopmentBoundaryPolicyTest.token("human-a");
        String receiver = DevelopmentBoundaryPolicyTest.token("human-b");
        String mine = "/v2/consultation/human-requests/mine";
        mvc.perform(get(mine).header("Authorization", receiver)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", owner)
                .header("Idempotency-Key", "opinion-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "goal", "查找并比较用于温度调节的传感器、控制器和阀门方案"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("taskId").asText();
        String route = "/v2/consultation/tasks/" + task + "/human-requests";
        String requestId = json(mvc.perform(post(route).header("Authorization", owner)
                .header("Idempotency-Key", "opinion-request-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":0,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("requestId").asText();
        String body = "{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,\"requestId\":\""
                + requestId + "\",\"opinionInputId\":\"OPINION_GAP\"}";
        String path = route + "/1/opinion";
        int receipts = count("con2_receipt", task);
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "no-accept-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        mvc.perform(post(route + "/1/accept").header("Authorization", receiver)
                .header("Idempotency-Key", "opinion-accept-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,\"requestId\":\""
                        + requestId + "\"}"))
                .andExpect(status().isOk());
        receipts++;
        mvc.perform(get(mine).header("Authorization", receiver)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value(requestId));
        mvc.perform(get(mine).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", owner).header("Idempotency-Key", "wrong-human-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent"))
                .header("Idempotency-Key", "agent-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "decimal-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"expectedTaskRevision\":1",
                        "\"expectedTaskRevision\":1.5"))).andExpect(status().isUnprocessableEntity());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        JsonNode first = json(mvc.perform(post(path).header("Authorization", receiver)
                .header("Idempotency-Key", "valid-opinion-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.submittedBy").value("dev-con-b"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.path("opinionText").asText()).contains("不形成技术对应结论");
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.opinionId").value(first.path("opinionId").asText()));
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("OPINION_GAP", "OTHER")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "other-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.opinionId").value(first.path("opinionId").asText()));
        assertThat(count("con2_human_opinion", task)).isEqualTo(1);
        assertThat(mvc.perform(get(mine).header("Authorization", receiver)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).doesNotContain(requestId);
        assertThat(count("con2_receipt", task)).isEqualTo(receipts + 1);
        mvc.perform(post(route + "/1/return").header("Authorization", receiver)
                .header("Idempotency-Key", "return-after-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedTaskRevision\":1,"
                        + "\"expectedRequestSequence\":1,\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isConflict());
        assertThat(count("con2_human_return", task)).isZero();
        assertThat(count("con2_receipt", task)).isEqualTo(receipts + 1);
        String nextId = json(mvc.perform(post(route).header("Authorization", owner)
                .header("Idempotency-Key", "opinion-new-package-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("requestId").asText();
        assertThat(nextId).isNotEqualTo(requestId);
        mvc.perform(get(route + "/1").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentInput").value(false))
                .andExpect(jsonPath("$.acceptanceState").value("ACCEPTED"));
        mvc.perform(get(route + "/current").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestSequence").value(2))
                .andExpect(jsonPath("$.request.currentInput").value(true))
                .andExpect(jsonPath("$.request.acceptanceState").value("NOT_ACCEPTED"));
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.opinionId").value(first.path("opinionId").asText()))
                .andExpect(jsonPath("$.currentInput").value(false));
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", owner)
                .header("Idempotency-Key", "opinion-supplement-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"message\":\"补充：该方案使用温度传感器、自动控制器和阀门。\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentInput").value(false));
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-opinion-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }
    private JsonNode json(String response) throws Exception { return mapper.readTree(response); }
}
