package cn.xinji.agentplatform.infrastructure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static cn.xinji.agentplatform.infrastructure.Dm8MigrationScripts.executeSafe;
import static cn.xinji.agentplatform.infrastructure.Dm8MigrationScripts.script;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTextResultStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class Dm8DevTaskCoreIntegrationTest {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";
    @Test void migrationTwoStoreInstancesFencingExpiryCleanupAndRollback() throws Exception {
        String url = System.getenv("DM8_JDBC_URL");
        String admin = System.getenv("DM8_JDBC_USER");
        String password = System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url != null && admin != null && password != null,
                "Local isolated DM8 credentials are not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        String schema = "V2C" + HexFormat.of().formatHex(random).toUpperCase();
        String temporaryPassword = "T9a" + HexFormat.of().formatHex(random) + "K7z";
        boolean created = false;
        try (Connection control = DriverManager.getConnection(url, admin, password)) {
            executeSafe(control, "CREATE USER " + schema + " IDENTIFIED BY \"" + temporaryPassword + "\"",
                    "create isolated schema");
            created = true;
            executeSafe(control, "GRANT RESOURCE TO " + schema, "grant isolated schema resources");
            var source = new DriverManagerDataSource(url, schema, temporaryPassword);
            source.setDriverClassName("dm.jdbc.driver.DmDriver");
            try (Connection isolated = source.getConnection()) {
                script(isolated, "V023__consultation_v2_dev_task_core.sql");
                script(isolated, "V024__consultation_v2_text_results.sql");
                script(isolated, "V025__consultation_v2_feature_confirmations.sql");
                script(isolated, "V026__consultation_v2_human_requests.sql");
                script(isolated, "V027__consultation_v2_human_acceptance.sql");
                script(isolated, "V028__consultation_v2_human_opinions.sql"); script(isolated, "V029__consultation_v2_human_returns.sql");
                script(isolated, "V030__consultation_v2_dev_corrections.sql");
                script(isolated, "V031__consultation_v2_dev_purpose_selection.sql");
            }
            Exception primaryFailure = null;
            try {
                JdbcTemplate sql = new JdbcTemplate(source);
                var manager1 = new DataSourceTransactionManager(source);
                var manager2 = new DataSourceTransactionManager(source);
                ZoneId zone = detectedSessionZone(sql);
                var first = new Dm8DevTaskCoreStore(source, manager1, zone);
                var restarted = new Dm8DevTaskCoreStore(source, manager2, zone);
                Actor human = new Actor("dev-con-a", "dev-con-isolated", 1, Instant.parse("2027-03-24T00:00:00Z"));
                Actor agent = new Actor("dev-agent", "dev-con-isolated", 1, Instant.parse("2027-03-24T00:00:00Z"));
                Actor job = new Actor("dev-job", "dev-con-isolated", 1, Instant.parse("2027-03-24T00:00:00Z"));
                Guard guard = permissiveSyntheticGuard();
                String key = "dm8-create-12345";
                var createdTask = first.create(human, key, GOAL, guard);
                assertThat(restarted.create(human, key, GOAL, guard).receiptId())
                        .isEqualTo(createdTask.receiptId());
                assertThatThrownBy(() -> restarted.create(human, key, MESSAGE, guard))
                        .isInstanceOf(DevTaskCoreFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
                String taskId = createdTask.taskId();
                assertThat(restarted.read(human, taskId, guard).messages().getFirst().body()).isEqualTo(GOAL);
                var appended = restarted.append(human, taskId, "dm8-append-12345", 1, MESSAGE, guard);
                assertThat(appended.revision()).isEqualTo(2);
                assertThatThrownBy(() -> first.append(human, taskId, "dm8-race-1234567", 1, MESSAGE, guard))
                        .isInstanceOf(DevTaskCoreFailure.class).hasMessage("VERSION_CONFLICT");
                String attempt = sql.queryForObject("SELECT attempt_id FROM con2_attempt "
                        + "WHERE task_id=? AND input_revision=2", String.class, taskId);
                var lease1 = first.lease(agent, attempt, guard);
                sql.update("UPDATE con2_lease SET lease_until=? WHERE task_id=?",
                        Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(5), ZoneOffset.UTC)), taskId);
                var lease2 = restarted.lease(agent, attempt, guard);
                assertThat(lease2.fencingToken()).isGreaterThan(lease1.fencingToken());
                assertThatThrownBy(() -> first.checkpoint(agent, attempt, lease1.fencingToken(),
                        "message:2", guard)).isInstanceOf(DevTaskCoreFailure.class).hasMessage("STALE_TOKEN");
                restarted.checkpoint(agent, attempt, lease2.fencingToken(), "message:2", guard);
                assertThat(first.read(human, taskId, guard).checkpoints()).hasSize(1);
                first.cancel(human, taskId, "dm8-cancel-12345", 2, guard);
                assertThatThrownBy(() -> restarted.lease(agent, attempt, guard))
                        .isInstanceOf(DevTaskCoreFailure.class).hasMessage("STALE_ATTEMPT");
                sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                        Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), taskId);
                assertThatThrownBy(() -> restarted.read(human, taskId, guard))
                        .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
                assertThat(restarted.reconcile(job, guard).removed()).isEqualTo(1);
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isZero();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt", Integer.class)).isZero();
                String retryTask = first.create(human, "dm8-purge-failure-12345", GOAL, guard).taskId();
                sql.execute("CREATE TABLE con2_test_retention_hold(task_id VARCHAR(36) PRIMARY KEY, "
                        + "CONSTRAINT con2_test_retention_fk FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
                try {
                    sql.update("INSERT INTO con2_test_retention_hold(task_id) VALUES (?)", retryTask);
                    sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                            Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1),
                                    ZoneOffset.UTC)), retryTask);
                    assertThat(restarted.reconcile(job, guard).failed()).isEqualTo(1);
                    assertThat(sql.queryForObject("SELECT state FROM con2_task WHERE task_id=?",
                            String.class, retryTask)).isEqualTo("PURGING");
                    assertThatThrownBy(() -> first.read(human, retryTask, guard))
                            .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
                    sql.update("DELETE FROM con2_test_retention_hold WHERE task_id=?", retryTask);
                    assertThat(first.reconcile(job, guard).removed()).isEqualTo(1);
                    assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isZero();
                } finally {
                    sql.execute("DROP TABLE con2_test_retention_hold");
                }
                concurrentWritesAndConnectionRecovery(source, first, restarted, human, job, guard, zone);
                Dm8DevTaskCoreProcessProbe.independentHttpProcessAndRestart(source, schema, temporaryPassword, zone);
                textResultsAcrossConnections(source, human, job, guard, zone);
                DevFeatureConfirmationDm8Scenarios.acrossConnections(source, human, job, guard, zone);
                DevHumanRequestDm8Scenarios.acrossConnections(source, human, job, guard, zone);
                DevHumanAcceptanceDm8Scenarios.acrossConnections(source, human, job, guard, zone);
                DevHumanReturnDm8Scenarios.acrossConnections(source, human, job, guard, zone);
                DevCorrectionDm8Scenarios.acrossConnections(source, human, job, guard, zone);
                DevPurposeSelectionDm8Scenarios.acrossConnections(source, human, job, guard, zone);
            } catch (Exception failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                try (Connection isolated = source.getConnection()) {
                    script(isolated, "U031__consultation_v2_dev_purpose_selection.sql");
                    script(isolated, "U030__consultation_v2_dev_corrections.sql");
                    script(isolated, "U029__consultation_v2_human_returns.sql"); script(isolated, "U028__consultation_v2_human_opinions.sql");
                    script(isolated, "U027__consultation_v2_human_acceptance.sql");
                    script(isolated, "U026__consultation_v2_human_requests.sql");
                    script(isolated, "U025__consultation_v2_feature_confirmations.sql");
                    script(isolated, "U024__consultation_v2_text_results.sql");
                    script(isolated, "U023__consultation_v2_dev_task_core.sql");
                } catch (Exception cleanupFailure) {
                    if (primaryFailure == null) throw cleanupFailure;
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            }
        } finally {
            if (created) {
                try (Connection control = DriverManager.getConnection(url, admin, password)) {
                    executeSafe(control, "DROP USER " + schema + " CASCADE", "remove own temporary schema");
                }
            }
        }
    }
    private static void textResultsAcrossConnections(DriverManagerDataSource source, Actor human, Actor job,
                                                      Guard guard, ZoneId zone) throws Exception {
        JdbcTemplate sql = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        var taskStore = new Dm8DevTaskCoreStore(source, manager, zone);
        var first = new Dm8DevTextResultStore(source, manager, zone);
        var restarted = new Dm8DevTextResultStore(source, new DataSourceTransactionManager(source), zone);
        var workState = new DevCurrentWorkStateDm8Scenarios(source, zone, human, guard);
        var manifest = new ObjectMapper().readTree(WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-synthetic-v1", "manifest.json")).toFile());
        var records = manifest.path("datasets").get(0).path("records");
        AtomicBoolean permitted = new AtomicBoolean(true);
        DevTextResultStore.SourceGuard sources = (actor, id, version, digest, query, save, display, now) -> {
            if (!permitted.get() || !actor.principal().equals(human.principal())) return null;
            for (var record : records) {
                if (!record.path("recordId").asText().equals(id)
                        || !record.path("sourceVersion").asText().equals(version)
                        || digest != null && !record.path("contentSha256").asText().equals(digest)) continue;
                return new DevTextResultStore.Source(id, version, record.path("contentSha256").asText(),
                        record.path("locator").asText(), record.path("claimText").asText());
            }
            return null;
        };
        String task = taskStore.create(human, "text-dm8-create-12345", GOAL, guard).taskId();
        workState.empty(task);
        var s1 = first.search(human, task, "text-dm8-search-12345", 1, 0, guard, sources);
        assertThat(s1.members().get(1).recordId()).isEqualTo("P2");
        assertThat(restarted.search(human, task, "text-dm8-search-12345", 1, 0, guard, sources).setId())
                .isEqualTo(s1.setId());
        var focus = restarted.focus(human, task, "text-dm8-focus-12345", s1.setId(), 2, 1, 0, guard, sources);
        assertThat(focus.recordId()).isEqualTo("P2");
        var s2 = first.search(human, task, "text-dm8-search-two", 1, 1, guard, sources);
        assertThat(s2.members().getFirst().recordId()).isEqualTo("P2");
        workState.restored(task, s1.setId(), s2.setId());
        assertThat(restarted.read(human, task, s1.setId(), guard, sources).members().get(1).recordId())
                .isEqualTo("P2");
        int receipts = sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
        int events = sql.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?", Integer.class, task);
        int focuses = sql.queryForObject("SELECT COUNT(*) FROM con2_focus WHERE task_id=?", Integer.class, task);
        var prepared = first.prepareEvidence(human, task, s1.setId(), 2, guard, sources);
        assertThat(prepared.recordId()).isEqualTo("P2");
        assertThat(prepared.targetText()).isEqualTo(GOAL);
        assertThat(prepared.targetMessageRevision()).isEqualTo(1);
        assertThat(prepared.claimText()).isEqualTo("传感器、手动阀门");
        assertThat(prepared.historicalInput()).isFalse();
        DevInputDisplayDm8Scenarios.run(taskStore, restarted, human, job, task, s1.setId(),
                guard, sources, sql, receipts, events, GOAL, MESSAGE);
        assertThatThrownBy(() -> restarted.prepareEvidence(human, task, s1.setId(), 3, guard, sources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("INVALID_INPUT");
        Actor other = new Actor("dev-con-b", human.organization(), 1, human.credentialUntil());
        assertThatThrownBy(() -> restarted.prepareEvidence(other, task, s1.setId(), 2, guard, sources))
                .isInstanceOf(DevTaskCoreFailure.class);
        taskStore.append(human, task, "text-dm8-append-12345", 1, MESSAGE, guard);
        workState.historical(task);
        assertThat(restarted.prepareEvidence(human, task, s1.setId(), 2, guard, sources).targetText())
                .isEqualTo(GOAL);
        assertThat(restarted.prepareEvidence(human, task, s1.setId(), 2, guard, sources).historicalInput())
                .isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts + 1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?", Integer.class, task))
                .isEqualTo(events + 1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_focus WHERE task_id=?", Integer.class, task))
                .isEqualTo(focuses);
        assertThat(sql.queryForObject("SELECT revision FROM con2_task WHERE task_id=?", Long.class, task))
                .isEqualTo(2L);
        permitted.set(false);
        workState.withdrawnReference(task, s2.setId());
        assertThat(first.read(human, task, s1.setId(), guard, sources).members().get(1).claimText()).isNull();
        var withdrawn = restarted.prepareEvidence(human, task, s1.setId(), 2, guard, sources);
        assertThat(withdrawn.sourceStatus()).isEqualTo("SOURCE_UNAVAILABLE");
        assertThat(withdrawn.claimText()).isNull();
        assertThatThrownBy(() -> restarted.focus(human, task, "text-dm8-focus-withdraw", s1.setId(),
                1, 2, 1, guard, sources)).isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        sql.execute("CREATE TABLE con2_test_text_hold(task_id VARCHAR(36) PRIMARY KEY, "
                + "CONSTRAINT con2_test_text_hold_fk FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
        sql.update("INSERT INTO con2_test_text_hold(task_id) VALUES (?)", task);
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        assertThat(taskStore.reconcile(job, guard).failed()).isEqualTo(1);
        assertThatThrownBy(() -> restarted.read(human, task, s1.setId(), guard, sources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        assertThatThrownBy(() -> first.prepareEvidence(human, task, s1.setId(), 2, guard, sources))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        workState.expired(task);
        sql.update("DELETE FROM con2_test_text_hold WHERE task_id=?", task);
        assertThat(taskStore.reconcile(job, guard).removed()).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_result_set", Integer.class)).isZero();
        sql.execute("ALTER TABLE con2_test_text_hold DROP CONSTRAINT con2_test_text_hold_fk");
        sql.execute("DROP TABLE con2_test_text_hold");
    }
    private static void concurrentWritesAndConnectionRecovery(DriverManagerDataSource source,
            Dm8DevTaskCoreStore first, Dm8DevTaskCoreStore second, Actor human, Actor job,
            Guard guard, ZoneId zone) throws Exception {
        JdbcTemplate sql = new JdbcTemplate(source);
        String key = "dm8-parallel-create-12345";
        DevTaskCoreStore.CommandResult one;
        DevTaskCoreStore.CommandResult two;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> {
                awaitRace(ready, go);
                return first.create(human, key, GOAL, guard);
            }, pool);
            var b = CompletableFuture.supplyAsync(() -> {
                awaitRace(ready, go);
                return second.create(human, key, GOAL, guard);
            }, pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            one = a.get(20, TimeUnit.SECONDS);
            two = b.get(20, TimeUnit.SECONDS);
        }
        assertThat(two.taskId()).isEqualTo(one.taskId());
        assertThat(two.receiptId()).isEqualTo(one.receiptId());
        String taskId = one.taskId();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> racedAppend(first, human, guard, taskId,
                    "dm8-parallel-append-a", ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> racedAppend(second, human, guard, taskId,
                    "dm8-parallel-append-b", ready, go), pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMMITTED", "VERSION_CONFLICT");
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_message WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_event WHERE task_id=?",
                Integer.class, taskId)).isEqualTo(2);
        var unavailableSource = new DriverManagerDataSource("jdbc:dm://127.0.0.1:1",
                source.getUsername(), source.getPassword());
        unavailableSource.setDriverClassName("dm.jdbc.driver.DmDriver");
        var disconnected = new Dm8DevTaskCoreStore(unavailableSource,
                new DataSourceTransactionManager(unavailableSource), zone);
        assertThatThrownBy(() -> disconnected.create(human, "dm8-disconnected-12345", GOAL, guard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isEqualTo(1);
        var recovered = second.create(human, "dm8-disconnected-12345", GOAL, guard);
        assertThat(second.create(human, "dm8-disconnected-12345", GOAL, guard).receiptId())
                .isEqualTo(recovered.receiptId());
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isEqualTo(2);
        Timestamp originalExpiry = sql.queryForObject("SELECT expires_at FROM con2_task WHERE task_id=?",
                Timestamp.class, taskId);
        first.read(human, taskId, guard);
        assertThat(sql.queryForObject("SELECT expires_at FROM con2_task WHERE task_id=?",
                Timestamp.class, taskId)).isEqualTo(originalExpiry);
        Timestamp databaseTime = sql.queryForObject("SELECT CURRENT_TIMESTAMP FROM DUAL", Timestamp.class);
        Instant boundary = databaseTime.toLocalDateTime().atZone(zone).toInstant();
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(boundary, ZoneOffset.UTC)), taskId);
        assertThatThrownBy(() -> second.read(human, taskId, guard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)),
                recovered.taskId());
        assertThat(first.reconcile(job, guard).removed()).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task", Integer.class)).isZero();
    }
    private static String racedAppend(Dm8DevTaskCoreStore store, Actor human, Guard guard,
            String taskId, String key, CountDownLatch ready, CountDownLatch go) {
        awaitRace(ready, go);
        try {
            store.append(human, taskId, key, 1, MESSAGE, guard);
            return "COMMITTED";
        } catch (DevTaskCoreFailure failure) {
            return failure.getMessage();
        }
    }
    private static void awaitRace(CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent writers did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
    private static ZoneId detectedSessionZone(JdbcTemplate sql) {
        Timestamp current = sql.queryForObject("SELECT CURRENT_TIMESTAMP FROM DUAL", Timestamp.class);
        for (ZoneId candidate : java.util.List.of(ZoneId.of("Asia/Shanghai"), ZoneOffset.UTC)) {
            if (Duration.between(current.toLocalDateTime().atZone(candidate).toInstant(), Instant.now()).abs()
                    .compareTo(Duration.ofSeconds(60)) < 0) return candidate;
        }
        throw new AssertionError("DM8 session clock zone must be explicit and match current time");
    }
    private static Guard permissiveSyntheticGuard() {
        return new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return "dev-con-a".equals(actor.principal()) && now.isBefore(actor.credentialUntil());
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return humanCurrent(actor, now) && ("GOAL".equals(kind) && GOAL.equals(text)
                        || "SUPPLEMENT".equals(kind) && MESSAGE.equals(text));
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return inputAllowed(actor, kind, text, now); }
            @Override public boolean delegationCurrent(String owner, String organization, long version,
                                                       Instant until, Instant now) {
                return "dev-con-a".equals(owner) && "dev-con-isolated".equals(organization)
                        && version == 1 && now.isBefore(until);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return now.isBefore(actor.credentialUntil()) && (role == ServiceRole.AGENT
                        ? "dev-agent".equals(actor.principal()) : "dev-job".equals(actor.principal()));
            }
        };
    }
}
