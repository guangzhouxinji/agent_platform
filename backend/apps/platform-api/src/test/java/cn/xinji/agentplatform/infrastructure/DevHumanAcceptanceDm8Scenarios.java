package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanAcceptanceStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanAcceptanceStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevHumanAcceptanceDm8Scenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";

    static void acrossConnections(DriverManagerDataSource source, Actor owner, Actor job,
                                  Guard original, ZoneId zone) throws Exception {
        JdbcTemplate sql = new JdbcTemplate(source);
        var tasks = new Dm8DevTaskCoreStore(source, new DataSourceTransactionManager(source), zone);
        var requests = new Dm8DevHumanRequestStore(source, new DataSourceTransactionManager(source), zone);
        var first = new Dm8DevHumanAcceptanceStore(source, new DataSourceTransactionManager(source), zone);
        var second = new Dm8DevHumanAcceptanceStore(source, new DataSourceTransactionManager(source), zone);
        Actor receiver = new Actor("dev-con-b", owner.organization(), 1, owner.credentialUntil());
        Guard guard = new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return List.of(owner.principal(), receiver.principal()).contains(actor.principal())
                        && now.isBefore(actor.credentialUntil());
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return original.inputAllowed(actor, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return original.inputDisplayAllowed(actor, kind, text, now);
            }
            @Override public boolean delegationCurrent(String actor, String org, long version,
                                                       Instant until, Instant now) {
                return original.delegationCurrent(actor, org, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return original.serviceCurrent(actor, role, now);
            }
        };
        DevHumanRequestStore.Source signed = new DevHumanRequestStore.Source("DEV-CON-HUMAN-GAP-01",
                "6931666dd71b0fe08623017210fe81d39a586e406b82b59e592aa20e882f0713",
                "31439a6a288a248220b910938e9b9ad4433bee235ea406568cdad8495464b33e",
                0, "请人工核对当前分析目的与证据缺口。");
        AtomicBoolean allowed = new AtomicBoolean(true);
        DevHumanRequestStore.SourceGuard requestSource = (actor, save, display, now) ->
                allowed.get() ? signed : null;
        DevHumanAcceptanceStore.SourceGuard acceptSource = (actor, now) -> allowed.get() ? signed : null;
        String task = tasks.create(owner, "dm8-accept-task-12345", GOAL, guard).taskId();
        var request = requests.create(owner, task, "dm8-accept-request-12345", 1, 0,
                "REQUEST_GAP", guard, requestSource);
        assertThat(first.pending(receiver, guard, acceptSource)).extracting(DevHumanAcceptanceStore.Pending::requestId)
                .contains(request.requestId());
        assertThatThrownBy(() -> tasks.read(receiver, task, guard)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("TASK_NOT_FOUND");
        int receipts = sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
        allowed.set(false);
        assertThatThrownBy(() -> first.accept(receiver, task, 1, "dm8-accept-no-source", 1, 1,
                request.requestId(), guard, acceptSource)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        allowed.set(true);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts);
        List<String> outcomes;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> race(first, receiver, task, request.requestId(),
                    "dm8-accept-race-b", guard, acceptSource, ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> race(second, owner, task, request.requestId(),
                    "dm8-accept-race-a", guard, acceptSource, ready, go), pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes).contains("ACCEPTED", "HUMAN_ALREADY_ACCEPTED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_acceptance WHERE task_id=?",
                Integer.class, task)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts + 1);
        assertThat(requests.read(owner, task, 1, guard, requestSource).acceptanceState()).isEqualTo("ACCEPTED");
        assertThat(first.pending(receiver, guard, acceptSource)).noneMatch(item -> item.taskId().equals(task));
        Actor selected = outcomes.getFirst().equals("ACCEPTED") ? receiver : owner;
        Actor other = selected == receiver ? owner : receiver;
        String opinionText = "当前分析目的尚未确认，逐项专业对应方法尚无获准依据；本次不形成技术对应结论。";
        String opinionSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(opinionText.getBytes(StandardCharsets.UTF_8)));
        var opinionSource = new DevHumanRequestStore.Source(signed.manifestId(), signed.manifestDigest(),
                opinionSha, 0, opinionText);
        DevHumanOpinionStore.SourceGuard opinionGuard = (actor, save, display, now) ->
                allowed.get() ? opinionSource : null;
        var opinionsA = new Dm8DevHumanOpinionStore(source, new DataSourceTransactionManager(source), zone);
        var opinionsB = new Dm8DevHumanOpinionStore(source, new DataSourceTransactionManager(source), zone);
        assertThat(opinionsA.mine(selected, guard, requestSource, opinionGuard))
                .extracting(DevHumanOpinionStore.Pending::requestId).contains(request.requestId());
        assertThat(opinionsB.mine(other, guard, requestSource, opinionGuard))
                .noneMatch(item -> item.taskId().equals(task));
        assertThatThrownBy(() -> opinionsA.submit(other, task, 1, "dm8-wrong-opinion-12345", 1, 1,
                request.requestId(), "OPINION_GAP", guard, opinionGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_NOT_FOUND");
        allowed.set(false);
        assertThatThrownBy(() -> opinionsA.submit(selected, task, 1, "dm8-no-opinion-source", 1, 1,
                request.requestId(), "OPINION_GAP", guard, opinionGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_OPINION_SOURCE_UNAVAILABLE");
        allowed.set(true);
        List<String> opinionOutcomes;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> raceOpinion(opinionsA, selected, task,
                    request.requestId(), "dm8-opinion-race-a", guard, opinionGuard, ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> raceOpinion(opinionsB, selected, task,
                    request.requestId(), "dm8-opinion-race-b", guard, opinionGuard, ready, go), pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            opinionOutcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(opinionOutcomes).contains("SUBMITTED", "HUMAN_OPINION_EXISTS");
        String winningKey = opinionOutcomes.getFirst().equals("SUBMITTED")
                ? "dm8-opinion-race-a" : "dm8-opinion-race-b";
        var opinion = opinionsB.read(owner, task, 1, guard, opinionGuard);
        assertThat(opinionsA.submit(selected, task, 1, winningKey, 1, 1,
                request.requestId(), "OPINION_GAP", guard, opinionGuard).opinionId()).isEqualTo(opinion.opinionId());
        assertThat(opinion.opinionText()).isEqualTo(opinionText);
        assertThat(opinionsB.mine(selected, guard, requestSource, opinionGuard))
                .noneMatch(item -> item.taskId().equals(task));
        allowed.set(false);
        assertThatThrownBy(() -> opinionsB.read(owner, task, 1, guard, opinionGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_OPINION_SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> opinionsA.submit(selected, task, 1, winningKey, 1, 1,
                request.requestId(), "OPINION_GAP", guard, opinionGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_OPINION_SOURCE_UNAVAILABLE");
        allowed.set(true);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_opinion WHERE task_id=?",
                Integer.class, task)).isEqualTo(1);
        sql.execute("CREATE TABLE con2_accept_test_hold(task_id VARCHAR(36) PRIMARY KEY, "
                + "CONSTRAINT con2_accept_test_hold_fk FOREIGN KEY(task_id) REFERENCES con2_task(task_id))");
        try {
            sql.update("INSERT INTO con2_accept_test_hold(task_id) VALUES (?)", task);
            sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
            assertThat(tasks.reconcile(job, guard).failed()).isGreaterThanOrEqualTo(1);
            assertThatThrownBy(() -> second.accept(receiver, task, 1, "dm8-accept-late-12345", 1, 1,
                    request.requestId(), guard, acceptSource)).isInstanceOf(DevTaskCoreFailure.class)
                    .hasMessage("TASK_EXPIRED");
            sql.update("DELETE FROM con2_accept_test_hold WHERE task_id=?", task);
            assertThat(tasks.reconcile(job, guard).removed()).isGreaterThanOrEqualTo(1);
        } finally {
            sql.execute("DROP TABLE con2_accept_test_hold");
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_acceptance WHERE task_id=?",
                Integer.class, task)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_opinion WHERE task_id=?",
                Integer.class, task)).isZero();
    }

    private static String race(Dm8DevHumanAcceptanceStore store, Actor actor, String task, String request,
            String key, Guard guard, DevHumanAcceptanceStore.SourceGuard source,
            CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        try {
            store.accept(actor, task, 1, key, 1, 1, request, guard, source);
            return "ACCEPTED";
        } catch (DevTaskCoreFailure failure) { return failure.getMessage(); }
    }

    private static String raceOpinion(Dm8DevHumanOpinionStore store, Actor actor, String task, String request,
            String key, Guard guard, DevHumanOpinionStore.SourceGuard source,
            CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Opinion race did not start");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        try {
            store.submit(actor, task, 1, key, 1, 1, request, "OPINION_GAP", guard, source);
            return "SUBMITTED";
        } catch (DevTaskCoreFailure failure) { return failure.getMessage(); }
    }
}
