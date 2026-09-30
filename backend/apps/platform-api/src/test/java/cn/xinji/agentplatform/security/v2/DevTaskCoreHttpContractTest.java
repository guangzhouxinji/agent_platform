package cn.xinji.agentplatform.security.v2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.infrastructure.WorkspaceTestPaths;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties = {
        "consultation.storage=memory",
        "spring.datasource.url=jdbc:h2:mem:v2core;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "consultation-v2.development.enabled=true",
        "consultation-v2.development.task-core-enabled=true",
        "consultation-v2.development.text-result-enabled=true",
        "consultation-v2.development.evidence-read-enabled=true",
        "consultation-v2.development.feature-confirm-enabled=true",
        "consultation-v2.development.comparison-reconfirm-enabled=true",
        "consultation-v2.development.input-preview-enabled=true",
        "consultation-v2.development.purpose-read-enabled=true",
        "consultation-v2.development.purpose-select-enabled=true",
        "consultation-v2.development.work-state-enabled=true",
        "consultation-v2.development.human-request-enabled=true",
        "consultation-v2.development.human-accept-enabled=true",
        "consultation-v2.development.human-opinion-enabled=true",
        "consultation-v2.development.human-return-enabled=true",
        "consultation-v2.development.human-context-enabled=true",
        "consultation-v2.development.correction-enabled=true",
        "consultation-v2.development.db-session-zone=Asia/Shanghai",
        "consultation-v2.development.credentials[0].key-id=human-a",
        "consultation-v2.development.credentials[0].secret-sha256=05c8803d79d80e0bd886261c24c0ea30838d7d0047c558c23367dc910c974b5c",
        "consultation-v2.development.credentials[0].principal-ref=dev-con-a",
        "consultation-v2.development.credentials[0].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[0].kind=HUMAN",
        "consultation-v2.development.credentials[0].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[1].key-id=human-b",
        "consultation-v2.development.credentials[1].secret-sha256=0e0d6b169e418021fc74cbf168abc8ab822819f9f03c1aa63968256286d3d272",
        "consultation-v2.development.credentials[1].principal-ref=dev-con-b",
        "consultation-v2.development.credentials[1].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[1].kind=HUMAN",
        "consultation-v2.development.credentials[1].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[2].key-id=agent",
        "consultation-v2.development.credentials[2].secret-sha256=d0c5f1263ab7a8473a3135bbc5f391689660c2ce3bc0f783df20b86f307308f5",
        "consultation-v2.development.credentials[2].principal-ref=dev-con-agent",
        "consultation-v2.development.credentials[2].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[2].kind=AGENT",
        "consultation-v2.development.credentials[2].expires-at=2099-01-01T00:00:00Z",
        "consultation-v2.development.credentials[3].key-id=job",
        "consultation-v2.development.credentials[3].secret-sha256=93b3e3315ec272c5ef812cd9f78147a815fc45a5b7e646ea0095aa4c5cdf8b03",
        "consultation-v2.development.credentials[3].principal-ref=dev-con-job",
        "consultation-v2.development.credentials[3].organization-ref=dev-con-isolated",
        "consultation-v2.development.credentials[3].kind=JOB",
        "consultation-v2.development.credentials[3].expires-at=2099-01-01T00:00:00Z"
})
@ActiveProfiles("test-synthetic")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DevTaskCoreHttpContractTest {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DevelopmentSubjectPolicy subjects;
    @Autowired SyntheticUsePolicy sources;
    @Autowired DevTaskCoreStore taskCore;
    @Autowired DevTextResultStore textResults;
    @DynamicPropertySource
    static void manifests(DynamicPropertyRegistry registry) {
        registry.add("spring.sql.init.schema-locations", () -> WorkspaceTestPaths.root()
                .resolve(Path.of("db", "dm8", "V023__consultation_v2_dev_task_core.sql"))
                .toUri() + "," + WorkspaceTestPaths.root()
                .resolve(Path.of("db", "dm8", "V024__consultation_v2_text_results.sql")).toUri() + "," 
                + WorkspaceTestPaths.root()
                .resolve(Path.of("db", "dm8", "V025__consultation_v2_feature_confirmations.sql")).toUri() + ","
                + WorkspaceTestPaths.root()
                .resolve(Path.of("db", "dm8", "V026__consultation_v2_human_requests.sql")).toUri() + ","
                + WorkspaceTestPaths.root()
                .resolve(Path.of("db", "dm8", "V027__consultation_v2_human_acceptance.sql")).toUri() + ","
                + WorkspaceTestPaths.root().resolve(Path.of("db", "dm8",
                        "V028__consultation_v2_human_opinions.sql")).toUri() + ","
                + WorkspaceTestPaths.root().resolve(Path.of("db", "dm8",
                        "V029__consultation_v2_human_returns.sql")).toUri() + ","
                + WorkspaceTestPaths.root().resolve(Path.of("db", "dm8",
                        "V030__consultation_v2_dev_corrections.sql")).toUri() + ","
                + WorkspaceTestPaths.root().resolve(Path.of("db", "dm8",
                        "V031__consultation_v2_dev_purpose_selection.sql")).toUri());
        registry.add("consultation-v2.development.manifest-path",
                () -> DevelopmentBoundaryPolicyTest.manifest().toString());
        registry.add("consultation-v2.development.task-input-manifest-path",
                () -> DevelopmentBoundaryPolicyTest.manifest().resolveSibling("task-input-manifest.json").toString());
        registry.add("consultation-v2.development.comparison-input-manifest-path",
                () -> WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                        "dev-comparison-input-v1", "manifest.json")).toString());
        registry.add("consultation-v2.development.feature-candidate-manifest-path",
                () -> WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                        "dev-feature-candidates-v1", "manifest.json")).toString());
        registry.add("consultation-v2.development.human-request-manifest-path",
                () -> WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                        "dev-human-gap-v1", "manifest.json")).toString());
        registry.add("consultation-v2.development.correction-input-manifest-path",
                () -> WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                        "dev-correction-input-v1", "manifest.json")).toString());
    }
    @Test @Order(1) void createReplayAppendCancelAndOwnerIsolationAreAtomic() throws Exception {
        String key = "create-" + UUID.randomUUID();
        String payload = mapper.writeValueAsString(java.util.Map.of("goal", GOAL));
        JsonNode created = json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String task = created.path("taskId").asText();
        assertThat(created.path("revision").asLong()).isEqualTo(1);
        mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.receiptId")
                        .value(created.path("receiptId").asText()));
        mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"goal\":\"different\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization",
                DevelopmentBoundaryPolicyTest.token("human-b"))).andExpect(status().isNotFound());
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "append-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "append-other-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", human()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].body").value(MESSAGE));
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", human())
                .header("Idempotency-Key", "cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "after-cancel-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 3))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLOSED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_message WHERE task_id=?", Integer.class, task))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(3);
    }
    @Test @Order(2) void leaseFencesOldWorkerAndRetentionRemovesOwnedRows() throws Exception {
        String task = create("worker-" + UUID.randomUUID());
        String attempt = jdbc.queryForObject("SELECT attempt_id FROM con2_attempt WHERE task_id=?", String.class, task);
        String route = "/v2/internal/consultation/attempts/" + attempt;
        JsonNode first = json(mvc.perform(post(route + "/lease").header("Authorization", agent()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "busy-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_BUSY"));
        jdbc.update("UPDATE con2_lease SET lease_until=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(5), ZoneOffset.UTC)), task);
        JsonNode second = json(mvc.perform(post(route + "/lease").header("Authorization", agent()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(second.path("fencingToken").asLong()).isGreaterThan(first.path("fencingToken").asLong());
        mvc.perform(post(route + "/checkpoint").header("Authorization", agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":" + first.path("fencingToken").asLong()
                        + ",\"committedRef\":\"message:1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_TOKEN"));
        mvc.perform(post(route + "/checkpoint").header("Authorization", agent())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":" + second.path("fencingToken").asLong()
                        + ",\"committedRef\":\"message:1\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_checkpoint WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
        jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", human()))
                .andExpect(status().isGone());
        mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_task WHERE task_id=?", Integer.class, task))
                .isZero();
    }
    @Test @Order(3) void concurrentVersionAndIdempotencyAllowOneBusinessChange() throws Exception {
        String task = create("concurrent-" + UUID.randomUUID());
        String path = "/v2/consultation/tasks/" + task + "/messages";
        String content = mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 1));
        String winner;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var one = CompletableFuture.supplyAsync(() -> racedAppend(path, "race-one-" + task,
                    content, ready, go), pool);
            var two = CompletableFuture.supplyAsync(() -> racedAppend(path, "race-two-" + task,
                    content, ready, go), pool);
            ready.await();
            go.countDown();
            int first = one.join(), second = two.join();
            assertThat(java.util.List.of(first, second)).containsExactlyInAnyOrder(200, 409);
            winner = first == 200 ? "race-one-" + task : "race-two-" + task;
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_message WHERE task_id=?", Integer.class, task))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(2);
        mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", winner)
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isOk());
    }
    @Test @Order(4) void failedPurgeRemainsHiddenAndRetriesAfterDependencyClears() throws Exception {
        String task = create("purge-" + UUID.randomUUID());
        jdbc.execute("CREATE TABLE test_retention_hold(task_id VARCHAR(36) PRIMARY KEY, "
                + "CONSTRAINT fk_test_retention_hold FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
        try {
            jdbc.update("INSERT INTO test_retention_hold(task_id) VALUES (?)", task);
            jdbc.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1),
                            ZoneOffset.UTC)), task);
            mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));
            assertThat(jdbc.queryForObject("SELECT state FROM con2_task WHERE task_id=?", String.class, task))
                    .isEqualTo("PURGING");
            mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", human()))
                    .andExpect(status().isGone());
            jdbc.update("DELETE FROM test_retention_hold WHERE task_id=?", task);
            mvc.perform(post("/v2/internal/consultation/retention/reconcile").header("Authorization", job()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(1));
        } finally {
            jdbc.execute("DROP TABLE test_retention_hold");
        }
    }
    @Test @Order(5) void newInputKeepsFencingTokenIncreasingAndCancelRejectsLateCheckpoint() throws Exception {
        String task = create("epoch-" + UUID.randomUUID());
        String firstAttempt = jdbc.queryForObject("SELECT attempt_id FROM con2_attempt WHERE task_id=?",
                String.class, task);
        JsonNode first = json(mvc.perform(post("/v2/internal/consultation/attempts/{id}/lease", firstAttempt)
                .header("Authorization", agent())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        jdbc.update("UPDATE con2_lease SET lease_until=? WHERE task_id=?",
                java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        mvc.perform(post("/v2/consultation/tasks/{id}/messages", task).header("Authorization", human())
                .header("Idempotency-Key", "new-input-" + task).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("message", MESSAGE, "expectedVersion", 1))))
                .andExpect(status().isOk());
        String secondAttempt = jdbc.queryForObject("SELECT attempt_id FROM con2_attempt "
                + "WHERE task_id=? AND input_revision=2", String.class, task);
        JsonNode second = json(mvc.perform(post("/v2/internal/consultation/attempts/{id}/lease", secondAttempt)
                .header("Authorization", agent())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(second.path("fencingToken").asLong()).isGreaterThan(first.path("fencingToken").asLong());
        mvc.perform(post("/v2/internal/consultation/attempts/{id}/checkpoint", firstAttempt)
                .header("Authorization", agent()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":" + first.path("fencingToken").asLong()
                        + ",\"committedRef\":\"message:1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/v2/consultation/tasks/{id}/cancel", task).header("Authorization", human())
                .header("Idempotency-Key", "cancel-late-" + task).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v2/internal/consultation/attempts/{id}/checkpoint", secondAttempt)
                .header("Authorization", agent()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":" + second.path("fencingToken").asLong()
                        + ",\"committedRef\":\"message:2\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_checkpoint WHERE task_id=?", Integer.class, task))
                .isZero();
    }
    @Test @Order(6) void concurrentCreateWithSameKeyReturnsOneCommittedReceipt() throws Exception {
        String key = "same-key-" + UUID.randomUUID();
        String content = mapper.writeValueAsString(java.util.Map.of("goal", GOAL));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var first = CompletableFuture.supplyAsync(() -> racedCreate(key, content, ready, go), pool);
            var second = CompletableFuture.supplyAsync(() -> racedCreate(key, content, ready, go), pool);
            ready.await();
            go.countDown();
            JsonNode a = first.join(), b = second.join();
            assertThat(a.path("taskId").asText()).isEqualTo(b.path("taskId").asText());
            assertThat(a.path("receiptId").asText()).isEqualTo(b.path("receiptId").asText());
        }
    }
    @Test @Order(7) void evidencePreparationIsReadOnlyAndPinnedToHistoricalInput() throws Exception {
        new DevTextResultContractScenarios(mvc, mapper, jdbc, sources).evidencePreparation(); }
    @Test @Order(8) void textResultsKeepStableOrdinalAndDoNotChangeMessageRevision() throws Exception {
        new DevTextResultContractScenarios(mvc, mapper, jdbc, sources).stableResultAndCleanup(); }
    @Test @Order(9) void textResultRejectsWrongOwnerOrdinalAndBusyAttempt() throws Exception {
        new DevTextResultContractScenarios(mvc, mapper, jdbc, sources).rejectsInvalidSelectionAndBusyTask(); }
    @Test @Order(10) void concurrentSearchAndSourceWithdrawalDoNotPublishUnlicensedText() throws Exception {
        new DevTextResultContractScenarios(mvc, mapper, jdbc, sources).concurrencyAndWithdrawal(); }
    @Test @Order(11) void featureConfirmationPinsHumanInputAndIndependentRevision() throws Exception {
        new DevFeatureConfirmationContractScenarios(mvc, mapper, jdbc).confirmationAndHistory(); }
    @Test @Order(12) void featureConfirmationCompetesAndRetentionRemovesNewRelations() throws Exception {
        new DevFeatureConfirmationContractScenarios(mvc, mapper, jdbc).concurrencyAndRetention(); }
    @Test @Order(12) void featureConfirmationSerializesWithSupplementAndCancel() throws Exception {
        new DevFeatureConfirmationContractScenarios(mvc, mapper, jdbc).confirmationVersusTaskChanges(); }
    @Test @Order(12) void comparisonInputRequiresNewHumanConfirmation() throws Exception {
        new DevFeatureConfirmationContractScenarios(mvc, mapper, jdbc).newManifestRequiresExplicitConfirmation(); }
    @Test @Order(9) void comparisonInputPreviewBindsConfirmedFeaturesAndFocus() throws Exception {
        new DevComparisonInputPreviewContractScenarios(mvc, mapper, jdbc).previewAndFailures(); }
    @Test @Order(9) void purposeSelectionRequiresExplicitCurrentInputAndRetainsHistory() throws Exception {
        new DevPurposeSelectionContractScenarios(mvc, mapper, jdbc).selectionAndFailures(); }
    @Test @Order(9) void currentWorkStateRestoresExactReferencesWithoutContentOrWrites() throws Exception {
        new DevCurrentWorkStateContractScenarios(mvc, mapper, jdbc).restoreAndReject();
    }
    @Test @Order(0) void oldSavePermissionCannotDisplayTaskOrEvidenceBody() throws Exception {
        new DevInputDisplayContractScenarios(mvc, mapper, jdbc, taskCore, textResults).oldSaveCannotDisplay();
    }
    @Test @Order(13) void rejectsSpoofedInputsAndRevocationWithoutWrites() throws Exception {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class);
        mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", "fake-owner-" + UUID.randomUUID())
                .header("X-V0-Requester", "dev-con-b").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("goal", GOAL,
                        "ownerRef", "dev-con-b"))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/v2/internal/consultation/retention/reconcile")
                .header("Authorization", human())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isEqualTo(before);
        String task = create("revoked-" + UUID.randomUUID());
        subjects.revoke("human-a");
        mvc.perform(get("/v2/consultation/tasks/{id}", task).header("Authorization", human()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", "revoked-repeat-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("goal", GOAL))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM con2_message WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
    }
    @Test @Order(12) void humanRequestIsExplicitPendingAndVersionedWithoutFalseAcceptance() throws Exception {
        new DevHumanRequestContractScenarios(mvc, mapper, jdbc).requestHistoryAndFailures(); }
    @Test @Order(12) void humanAcceptanceIsExactAndSeparateFromTaskOwnership() throws Exception {
        new DevHumanAcceptanceContractScenarios(mvc, mapper, jdbc).acceptanceAndFailures(); }
    @Test @Order(12) void humanOpinionBelongsToTheExactAcceptedPackage() throws Exception {
        new DevHumanOpinionContractScenarios(mvc, mapper, jdbc).opinionAndHistory(); }
    @Test @Order(12) void humanReturnAndContextRespectTheExactPackage() throws Exception {
        new DevHumanReturnContractScenarios(mvc, mapper, jdbc).returnAndContinue();
        new DevHumanContextContractScenarios(mvc, mapper, jdbc).packageOnly(); }
    @Test @Order(13) void fixedCorrectionKeepsHistoryAndRejectsStaleCommands() throws Exception {
        new DevCorrectionContractScenarios(mvc, mapper, jdbc).correctAndContinue(); }
    private int racedAppend(String path, String key, String content, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            return mvc.perform(post(path).header("Authorization", human()).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(content))
                    .andReturn().getResponse().getStatus();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private JsonNode racedCreate(String key, String content, CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await();
            var response = mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            return mapper.readTree(response.getContentAsString());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
    private String create(String key) throws Exception {
        return json(mvc.perform(post("/v2/consultation/tasks").header("Authorization", human())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("goal", GOAL))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("taskId").asText();
    }
    private JsonNode json(String content) throws Exception { return mapper.readTree(content); }
    private static String human() { return DevelopmentBoundaryPolicyTest.token("human-a"); }
    private static String agent() { return DevelopmentBoundaryPolicyTest.token("agent"); }
    private static String job() { return DevelopmentBoundaryPolicyTest.token("job"); }
}
