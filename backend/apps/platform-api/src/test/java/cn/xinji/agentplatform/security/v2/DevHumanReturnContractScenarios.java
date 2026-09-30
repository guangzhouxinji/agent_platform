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

final class DevHumanReturnContractScenarios {
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevHumanReturnContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc; this.mapper = mapper; this.jdbc = jdbc;
    }

    void returnAndContinue() throws Exception {
        String owner = DevelopmentBoundaryPolicyTest.token("human-a");
        String receiver = DevelopmentBoundaryPolicyTest.token("human-b");
        String task = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", owner)
                .header("Idempotency-Key", "return-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.Map.of(
                        "goal", "查找并比较用于温度调节的传感器、控制器和阀门方案"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("taskId").asText();
        String base = "/v2/consultation/tasks/" + task + "/human-requests";
        String requestId = json(mvc.perform(post(base).header("Authorization", owner)
                .header("Idempotency-Key", "return-request-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":0,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("requestId").asText();
        String path = base + "/1/return";
        String body = "{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,\"requestId\":\""
                + requestId + "\"}";
        int receipts = count("con2_receipt", task);
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "not-accepted-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        mvc.perform(post(base + "/1/accept").header("Authorization", receiver)
                .header("Idempotency-Key", "return-accept-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk());
        receipts++;
        mvc.perform(post(path).header("Authorization", owner).header("Idempotency-Key", "wrong-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent"))
                .header("Idempotency-Key", "agent-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "decimal-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"expectedTaskRevision\":1",
                        "\"expectedTaskRevision\":1.5"))).andExpect(status().isUnprocessableEntity());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts);
        JsonNode returned = json(mvc.perform(post(path).header("Authorization", receiver)
                .header("Idempotency-Key", "valid-return-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.returnedBy").value("dev-con-b"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnId").value(returned.path("returnId").asText()));
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "valid-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body.replace(requestId, UUID.randomUUID().toString())))
                .andExpect(status().isConflict());
        mvc.perform(post(path).header("Authorization", receiver).header("Idempotency-Key", "another-return-12345")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(get(path).header("Authorization", receiver)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.returnId").value(returned.path("returnId").asText()));
        mvc.perform(get(base + "/1").header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptanceState").value("ACCEPTED"));
        mvc.perform(get("/v2/consultation/human-requests/mine").header("Authorization", receiver))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post(base + "/1/opinion").header("Authorization", receiver)
                .header("Idempotency-Key", "opinion-after-return-12345").contentType(MediaType.APPLICATION_JSON)
                .content(body.substring(0, body.length() - 1) + ",\"opinionInputId\":\"OPINION_GAP\"}"))
                .andExpect(status().isConflict());
        assertThat(count("con2_receipt", task)).isEqualTo(receipts + 1);
        String next = json(mvc.perform(post(base).header("Authorization", owner)
                .header("Idempotency-Key", "return-next-request-12345").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedRequestSequence\":1,"
                        + "\"requestInputId\":\"REQUEST_GAP\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("requestId").asText();
        assertThat(next).isNotEqualTo(requestId);
        mvc.perform(get(path).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentInput").value(false));
        assertThat(count("con2_human_return", task)).isEqualTo(1);
        assertThat(count("con2_human_acceptance", task)).isEqualTo(1);
    }

    private int count(String table, String task) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, task);
    }
    private JsonNode json(String response) throws Exception { return mapper.readTree(response); }
}
