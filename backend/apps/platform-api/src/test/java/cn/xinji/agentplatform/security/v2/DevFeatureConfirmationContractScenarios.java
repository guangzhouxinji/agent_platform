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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class DevFeatureConfirmationContractScenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    private static final String PAYLOAD = "{\"expectedTaskRevision\":2,\"expectedFeatureRevision\":0,"
            + "\"candidateManifestId\":\"DEV-CON-FEATURE-CANDIDATES-01\","
            + "\"featureIds\":[\"F1\",\"F2\",\"F3\"]}";
    private final MockMvc mvc;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    DevFeatureConfirmationContractScenarios(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    void newManifestRequiresExplicitConfirmation() throws Exception {
        String task = create();
        append(task);
        String path = path(task);
        String next = PAYLOAD.replace("\"expectedFeatureRevision\":0", "\"expectedFeatureRevision\":1")
                .replace("DEV-CON-FEATURE-CANDIDATES-01", "DEV-CON-COMPARE-INPUT-01");
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "old-reconfirm-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=? "
                + "AND manifest_id='DEV-CON-COMPARE-INPUT-01'", Integer.class, task)).isZero();
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "old-reconfirm-123")
                .contentType(MediaType.APPLICATION_JSON).content(next))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        String receipt = mapper.readTree(mvc.perform(post(path).header("Authorization", human())
                .header("Idempotency-Key", "new-reconfirm-123").contentType(MediaType.APPLICATION_JSON)
                .content(next)).andExpect(status().isOk()).andExpect(jsonPath("$.featureRevision").value(2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("receiptId").asText();
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "new-reconfirm-123")
                .contentType(MediaType.APPLICATION_JSON).content(next))
                .andExpect(status().isOk()).andExpect(jsonPath("$.receiptId").value(receipt));
        mvc.perform(get(path + "/1").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.featureRevision").value(1));
        mvc.perform(get(path + "/2").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.featureRevision").value(2));
        assertThat(jdbc.queryForObject("SELECT manifest_id FROM con2_feature_revision "
                + "WHERE task_id=? AND feature_seq=2", String.class, task)).isEqualTo("DEV-CON-COMPARE-INPUT-01");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                Integer.class, task)).isEqualTo(2);
    }

    void confirmationAndHistory() throws Exception {
        String task = create();
        String path = path(task);
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "feature-before-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        append(task);
        mvc.perform(post(path).header("Authorization", agent()).header("Idempotency-Key", "feature-agent-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "feature-invalid-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD.replace("\"F2\",", "")))
                .andExpect(status().isUnprocessableEntity());
        String key = "feature-confirm-123";
        JsonNode first = mapper.readTree(mvc.perform(post(path).header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(first.path("featureRevision").asLong()).isEqualTo(1);
        assertThat(first.path("inputRevision").asLong()).isEqualTo(2);
        assertThat(first.path("actorRef").asText()).isEqualTo("dev-con-a");
        assertThat(first.path("candidates").get(0).path("text").asText()).isEqualTo("温度传感器");
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptId").value(first.path("receiptId").asText()));
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD.replace("\"expectedFeatureRevision\":0",
                        "\"expectedFeatureRevision\":1")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", "feature-stale-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FEATURE_VERSION_CONFLICT"));
        mvc.perform(get(path + "/1").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(3));
        mvc.perform(get(path + "/1").header("Authorization",
                DevelopmentBoundaryPolicyTest.token("human-b"))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_member WHERE task_id=?",
                Integer.class, task)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT revision FROM con2_task WHERE task_id=?",
                Long.class, task)).isEqualTo(2);
        String digest = jdbc.queryForObject("SELECT body_sha FROM con2_message WHERE task_id=? AND revision=2",
                String.class, task);
        jdbc.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND revision=2", "0".repeat(64), task);
        mvc.perform(get(path + "/1").header("Authorization", human()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FEATURE_SOURCE_UNAVAILABLE"));
        jdbc.update("UPDATE con2_message SET body_sha=? WHERE task_id=? AND revision=2", digest, task);
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", human())
                .header("Idempotency-Key", "feature-cancel-123").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(get(path + "/1").header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.historicalInput").value(true));
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLOSED"));
    }

    void concurrencyAndRetention() throws Exception {
        String task = create();
        append(task);
        String path = path(task);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> raced(path, "feature-race-key-a", ready, go), pool);
            var second = CompletableFuture.supplyAsync(() -> raced(path, "feature-race-key-b", ready, go), pool);
            ready.await();
            go.countDown();
            assertThat(List.of(first.join(), second.join())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                Integer.class, task)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_member WHERE task_id=?",
                Integer.class, task)).isEqualTo(3);
        jdbc.execute("CREATE TABLE con2_feature_retention_hold(task_id VARCHAR(36) PRIMARY KEY, "
                + "FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
        try {
            jdbc.update("INSERT INTO con2_feature_retention_hold(task_id) VALUES (?)", task);
            jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(2), ZoneOffset.UTC)), task);
            mvc.perform(get(path + "/1").header("Authorization", human())).andExpect(status().isGone());
            mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));
            assertThat(jdbc.queryForObject("SELECT state FROM con2_task WHERE task_id=?",
                    String.class, task)).isEqualTo("PURGING");
            mvc.perform(get(path + "/1").header("Authorization", human())).andExpect(status().isGone());
            jdbc.update("DELETE FROM con2_feature_retention_hold WHERE task_id=?", task);
            mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                    Integer.class, task)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_member WHERE task_id=?",
                    Integer.class, task)).isZero();
        } finally {
            jdbc.execute("DROP TABLE con2_feature_retention_hold");
        }
    }

    void confirmationVersusTaskChanges() throws Exception {
        String supplemented = create();
        append(supplemented);
        String supplementPath = path(supplemented);
        List<Integer> supplementStatuses = race(supplementPath, supplemented, false);
        assertThat(supplementStatuses.get(1)).isEqualTo(200);
        assertThat(supplementStatuses.get(0)).isIn(200, 409);
        int supplementVersions = jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                Integer.class, supplemented);
        assertThat(supplementVersions).isEqualTo(supplementStatuses.get(0) == 200 ? 1 : 0);
        if (supplementVersions == 1) {
            mvc.perform(get(supplementPath + "/1").header("Authorization", human()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.historicalInput").value(true));
        }

        String cancelled = create();
        append(cancelled);
        List<Integer> cancelStatuses = race(path(cancelled), cancelled, true);
        assertThat(cancelStatuses.get(1)).isEqualTo(200);
        assertThat(cancelStatuses.get(0)).isIn(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_feature_revision WHERE task_id=?",
                Integer.class, cancelled)).isEqualTo(cancelStatuses.get(0) == 200 ? 1 : 0);
        mvc.perform(post(path(cancelled)).header("Authorization", human())
                .header("Idempotency-Key", "feature-after-cancel-123")
                .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLOSED"));
    }

    private List<Integer> race(String path, String task, boolean cancel) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var confirmation = CompletableFuture.supplyAsync(
                    () -> raced(path, "feature-change-" + task, ready, go), pool);
            var change = CompletableFuture.supplyAsync(() -> {
                try {
                    ready.countDown();
                    go.await();
                    String command = cancel ? "/v2/consultation/tasks/" + task + "/cancel"
                            : "/v2/consultation/tasks/" + task + "/messages";
                    String content = cancel ? "{\"expectedVersion\":2}"
                            : mapper.writeValueAsString(Map.of("message", MESSAGE, "expectedVersion", 2));
                    return mvc.perform(post(command).header("Authorization", human())
                            .header("Idempotency-Key", "feature-task-change-" + task)
                            .contentType(MediaType.APPLICATION_JSON).content(content))
                            .andReturn().getResponse().getStatus();
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }, pool);
            ready.await();
            go.countDown();
            return List.of(confirmation.join(), change.join());
        }
    }

    private String create() throws Exception {
        return mapper.readTree(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", "feature-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("taskId").asText();
    }

    private void append(String task) throws Exception {
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "feature-append-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isOk());
    }

    private int raced(String path, String key, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            return mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(PAYLOAD)).andReturn().getResponse().getStatus();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String path(String task) { return "/v2/consultation/tasks/" + task + "/feature-confirmations"; }
    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
    private static String agent() { return DevelopmentBoundaryPolicyTest.token("agent"); }
    private static String job() { return DevelopmentBoundaryPolicyTest.token("job"); }
}
