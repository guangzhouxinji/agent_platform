package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevHumanRequestDm8Scenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";
    private static final String MESSAGE = "补充：该方案使用温度传感器、自动控制器和阀门。";

    static void acrossConnections(DriverManagerDataSource source, Actor human, Actor job,
                                  Guard guard, ZoneId zone) throws Exception {
        var sql = new JdbcTemplate(source);
        var tasks = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        var first = new Dm8DevHumanRequestStore(source, new DataSourceTransactionManager(source), zone);
        var restarted = new Dm8DevHumanRequestStore(source, new DataSourceTransactionManager(source), zone);
        String task = tasks.create(human, "dm8-human-task-12345", GOAL, guard).taskId();
        AtomicBoolean allowed = new AtomicBoolean(true);
        DevHumanRequestStore.Source signed = new DevHumanRequestStore.Source("DEV-CON-HUMAN-GAP-01",
                "6931666dd71b0fe08623017210fe81d39a586e406b82b59e592aa20e882f0713",
                "31439a6a288a248220b910938e9b9ad4433bee235ea406568cdad8495464b33e",
                0, "请人工核对当前分析目的与证据缺口。");
        DevHumanRequestStore.SourceGuard sourceGuard = (actor, save, display, now) ->
                allowed.get() && actor.principal().equals(human.principal()) ? signed : null;
        assertThat(first.current(human, task, guard, sourceGuard).requestSequence()).isZero();
        assertThatThrownBy(() -> first.create(human, task, "dm8-human-no-source", 1, 0,
                "REQUEST_GAP", guard, (actor, save, display, now) -> null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
        AtomicInteger sourceChecks = new AtomicInteger();
        assertThatThrownBy(() -> first.create(human, task, "dm8-human-revoked-mid-write", 1, 0,
                "REQUEST_GAP", guard, (actor, save, display, now) ->
                        sourceChecks.incrementAndGet() == 1 ? signed : null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
        var disconnectedSource = new DriverManagerDataSource("jdbc:dm://127.0.0.1:1",
                source.getUsername(), source.getPassword());
        disconnectedSource.setDriverClassName("dm.jdbc.driver.DmDriver");
        var disconnected = new Dm8DevHumanRequestStore(disconnectedSource,
                new DataSourceTransactionManager(disconnectedSource), zone);
        assertThatThrownBy(() -> disconnected.create(human, task, "dm8-human-db-failure", 1, 0,
                "REQUEST_GAP", guard, sourceGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("DATABASE_UNAVAILABLE");
        List<DevHumanRequestStore.Request> results;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> race(first, human, task, guard, sourceGuard, ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> race(restarted, human, task, guard, sourceGuard, ready, go), pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            results = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(results.get(0).requestId()).isEqualTo(results.get(1).requestId());
        assertThat(results.get(0).acceptanceState()).isEqualTo("NOT_ACCEPTED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_request WHERE task_id=?",
                Integer.class, task)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?",
                Integer.class, task)).isEqualTo(2);
        assertThatThrownBy(() -> restarted.create(human, task, "dm8-human-request-same", 1, 1,
                "REQUEST_GAP", guard, sourceGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("IDEMPOTENCY_CONFLICT");
        allowed.set(false);
        assertThatThrownBy(() -> first.read(human, task, 1, guard, sourceGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        allowed.set(true);
        assertThat(restarted.read(human, task, 1, guard, sourceGuard).currentInput()).isTrue();
        tasks.append(human, task, "dm8-human-supplement-12345", 1, MESSAGE, guard);
        assertThat(first.read(human, task, 1, guard, sourceGuard).currentInput()).isFalse();
        assertThat(restarted.create(human, task, "dm8-human-second-12345", 2, 1,
                "REQUEST_GAP", guard, sourceGuard).requestSequence()).isEqualTo(2);
        tasks.cancel(human, task, "dm8-human-cancel-12345", 2, guard);
        assertThat(first.current(human, task, guard, sourceGuard).request().currentInput()).isFalse();
        assertThatThrownBy(() -> first.create(human, task, "dm8-human-closed-12345", 2, 2,
                "REQUEST_GAP", guard, sourceGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_CLOSED");
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        assertThatThrownBy(() -> restarted.read(human, task, 1, guard, sourceGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
        sql.execute("CREATE TABLE con2_human_test_hold(task_id VARCHAR(36) PRIMARY KEY, "
                + "CONSTRAINT con2_human_test_hold_fk FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
        try {
            sql.update("INSERT INTO con2_human_test_hold(task_id) VALUES (?)", task);
            assertThat(tasks.reconcile(job, guard).failed()).isGreaterThanOrEqualTo(1);
            assertThat(sql.queryForObject("SELECT state FROM con2_task WHERE task_id=?", String.class, task))
                    .isEqualTo("PURGING");
            assertThatThrownBy(() -> restarted.current(human, task, guard, sourceGuard))
                    .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
            sql.update("DELETE FROM con2_human_test_hold WHERE task_id=?", task);
            assertThat(tasks.reconcile(job, guard).removed()).isGreaterThanOrEqualTo(1);
        } finally {
            sql.execute("DROP TABLE con2_human_test_hold");
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_request WHERE task_id=?",
                Integer.class, task)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_request_state WHERE task_id=?",
                Integer.class, task)).isZero();
    }

    private static DevHumanRequestStore.Request race(Dm8DevHumanRequestStore store, Actor human, String task,
            Guard guard, DevHumanRequestStore.SourceGuard source, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return store.create(human, task, "dm8-human-request-same", 1, 0, "REQUEST_GAP", guard, source);
    }
}
