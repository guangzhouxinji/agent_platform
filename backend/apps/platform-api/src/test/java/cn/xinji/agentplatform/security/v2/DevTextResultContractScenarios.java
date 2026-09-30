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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevTextResultContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final SyntheticUsePolicy sources;

    DevTextResultContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc, SyntheticUsePolicy sources) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.sources = sources;
    }

    void evidencePreparation() throws Exception {
        String task = create("evidence-" + UUID.randomUUID());
        String path = "/v2/consultation/tasks/" + task;
        JsonNode s1 = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "evidence-search-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String set = s1.path("setId").asText();
        String evidence = path + "/text-searches/" + set + "/members/2/evidence-preparation";
        int receipts = jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
        mvc.perform(get(evidence).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordId").value("P2"))
                .andExpect(jsonPath("$.targetText").value(GOAL))
                .andExpect(jsonPath("$.targetMessageRevision").value(1))
                .andExpect(jsonPath("$.locator").value("claim:1"))
                .andExpect(jsonPath("$.claimText").value("传感器、手动阀门"))
                .andExpect(jsonPath("$.featureConfirmation").value("NOT_RECORDED"))
                .andExpect(jsonPath("$.technicalRelation").value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.historicalInput").value(false));
        mvc.perform(get(evidence).header("Authorization",
                DevelopmentBoundaryPolicyTest.token("human-b"))).andExpect(status().isNotFound());
        String otherTask = create("other-evidence-" + UUID.randomUUID());
        mvc.perform(get("/v2/consultation/tasks/" + otherTask + "/text-searches/" + set
                + "/members/2/evidence-preparation").header("Authorization", human()))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/text-searches/" + set + "/members/3/evidence-preparation")
                .header("Authorization", human())).andExpect(status().isUnprocessableEntity());
        mvc.perform(get(evidence).header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isForbidden());
        String digest = jdbc.queryForObject("SELECT content_sha FROM con2_result_member "
                + "WHERE set_id=? AND ordinal=2", String.class, set);
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2",
                "0".repeat(64), set);
        mvc.perform(get(evidence).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceStatus").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.claimText").isEmpty());
        jdbc.update("UPDATE con2_result_member SET content_sha=? WHERE set_id=? AND ordinal=2", digest, set);
        mvc.perform(post(path + "/messages").header("Authorization", human())
                .header("Idempotency-Key", "evidence-append-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message",
                        "补充：该方案使用温度传感器、自动控制器和阀门。", "expectedVersion", 1))))
                .andExpect(status().isOk());
        mvc.perform(get(evidence).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetText").value(GOAL))
                .andExpect(jsonPath("$.historicalInput").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_focus WHERE task_id=?", Integer.class, task))
                .isZero();
        mvc.perform(post(path + "/cancel").header("Authorization", human())
                .header("Idempotency-Key", "evidence-cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get(evidence).header("Authorization", human()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLOSED"));
        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(get(evidence).header("Authorization", human())).andExpect(status().isGone());
    }

    void stableResultAndCleanup() throws Exception {
        String task = create("text-" + UUID.randomUUID());
        String path = "/v2/consultation/tasks/" + task;
        String firstPayload = "{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}";
        String key = "search-" + task;
        JsonNode first = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.method").value("SYNTHETIC_FIXED_ORDER"))
                .andReturn().getResponse().getContentAsString());
        String s1 = first.path("setId").asText();
        assertThat(first.path("members").get(1).path("recordId").asText()).isEqualTo("P2");
        mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(firstPayload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setId").value(s1));
        mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        String selected = "{\"setId\":\"" + s1 + "\",\"ordinal\":2,"
                + "\"expectedTaskRevision\":1,\"expectedFocusSequence\":0}";
        mvc.perform(post(path + "/focus").header("Authorization", human())
                .header("Idempotency-Key", "focus-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(selected)).andExpect(status().isOk()).andExpect(jsonPath("$.recordId").value("P2"));
        mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "search-2-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members[0].recordId").value("P2"));
        mvc.perform(get(path + "/text-searches/" + s1).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members[1].recordId").value("P2"))
                .andExpect(jsonPath("$.members[1].claimText").value("传感器、手动阀门"));
        assertThat(jdbc.queryForObject("SELECT revision FROM con2_task WHERE task_id=?", Long.class, task))
                .isEqualTo(1L);
        mvc.perform(post(path + "/cancel").header("Authorization", human())
                .header("Idempotency-Key", "cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "after-cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(firstPayload)).andExpect(status().isConflict());
        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_result_set WHERE task_id=?", Integer.class, task))
                .isZero();
    }

    void concurrencyAndWithdrawal() throws Exception {
        String task = create("source-" + UUID.randomUUID());
        String route = "/v2/consultation/tasks/" + task + "/text-searches";
        String payload = "{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}";
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> racedSearch(route, "source-a-" + task,
                    payload, ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> racedSearch(route, "source-b-" + task,
                    payload, ready, go), pool);
            ready.await();
            go.countDown();
            assertThat(java.util.List.of(a.join(), b.join())).containsExactlyInAnyOrder(200, 409);
        }
        String set = jdbc.queryForObject("SELECT set_id FROM con2_result_set WHERE task_id=?", String.class, task);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_result_member WHERE set_id=?", Integer.class, set))
                .isEqualTo(2);
        sources.withdraw("DEV-CON-SYN-01");
        mvc.perform(get(route + "/" + set).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members[0].available").value(false))
                .andExpect(jsonPath("$.members[0].claimText").isEmpty());
        mvc.perform(get(route + "/" + set + "/members/1/evidence-preparation")
                .header("Authorization", human())).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value("SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.claimText").isEmpty());
        mvc.perform(post("/v2/consultation/tasks/{id}/focus", task).header("Authorization", human())
                .header("Idempotency-Key", "source-focus-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setId\":\"" + set + "\",\"ordinal\":1,"
                        + "\"expectedTaskRevision\":1,\"expectedFocusSequence\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_focus WHERE task_id=?", Integer.class, task))
                .isZero();
    }

    void rejectsInvalidSelectionAndBusyTask() throws Exception {
        String task = create("negative-" + UUID.randomUUID());
        String path = "/v2/consultation/tasks/" + task;
        String payload = "{\"expectedTaskRevision\":1,\"expectedResultSequence\":0}";
        mvc.perform(post(path + "/text-searches").header("Authorization",
                DevelopmentBoundaryPolicyTest.token("human-b"))
                .header("Idempotency-Key", "other-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isNotFound());
        JsonNode set = json(mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "negative-search-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String setId = set.path("setId").asText();
        mvc.perform(post(path + "/focus").header("Authorization", human())
                .header("Idempotency-Key", "ordinal-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setId\":\"" + setId + "\",\"ordinal\":3,"
                        + "\"expectedTaskRevision\":1,\"expectedFocusSequence\":0}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_focus WHERE task_id=?", Integer.class, task))
                .isZero();
        String attempt = jdbc.queryForObject("SELECT attempt_id FROM con2_attempt WHERE task_id=?",
                String.class, task);
        mvc.perform(post("/v2/internal/consultation/attempts/{id}/lease", attempt)
                .header("Authorization", DevelopmentBoundaryPolicyTest.token("agent")))
                .andExpect(status().isOk());
        mvc.perform(post(path + "/text-searches").header("Authorization", human())
                .header("Idempotency-Key", "busy-search-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedTaskRevision\":1,\"expectedResultSequence\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_BUSY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_result_set WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
    }

    private int racedSearch(String route, String key, String payload, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            return mvc.perform(post(route).header("Authorization", human()).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn().getResponse().getStatus();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private String create(String key) throws Exception {
        return json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("taskId").asText();
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
    private static String job() { return DevelopmentBoundaryPolicyTest.token("job"); }
}
