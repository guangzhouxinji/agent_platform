package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanAcceptanceStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanContextStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevHumanReturnStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevHumanReturnDm8Scenarios {
    private static final String GOAL = "查找并比较用于温度调节的传感器、控制器和阀门方案";

    static void acrossConnections(DriverManagerDataSource source, Actor owner, Actor job,
            Guard guard, ZoneId zone) throws Exception {
        var manager = new DataSourceTransactionManager(source);
        var tasks = new Dm8DevTaskCoreStore(source, manager, zone);
        var requests = new Dm8DevHumanRequestStore(source, manager, zone);
        var acceptance = new Dm8DevHumanAcceptanceStore(source, manager, zone);
        var first = new Dm8DevHumanReturnStore(source, new DataSourceTransactionManager(source), zone);
        var restarted = new Dm8DevHumanReturnStore(source, new DataSourceTransactionManager(source), zone);
        var opinions = new Dm8DevHumanOpinionStore(source, new DataSourceTransactionManager(source), zone);
        var sql = new JdbcTemplate(source);
        // The test guard must accept both independent development humans.
        Guard both = new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return List.of(owner.principal(), "dev-con-b").contains(actor.principal())
                        && now.isBefore(actor.credentialUntil());
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return guard.inputAllowed(owner, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return guard.inputDisplayAllowed(owner, kind, text, now);
            }
            @Override public boolean delegationCurrent(String actor, String org, long version,
                    Instant until, Instant now) {
                return guard.delegationCurrent(actor, org, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, cn.xinji.agentplatform.consultation.taskcore
                    .DevTaskCoreStore.ServiceRole role, Instant now) {
                return guard.serviceCurrent(actor, role, now);
            }
        };
        Actor receiver = new Actor("dev-con-b", owner.organization(), 1, owner.credentialUntil());
        var signed = new DevHumanRequestStore.Source("DEV-CON-HUMAN-GAP-01",
                "6931666dd71b0fe08623017210fe81d39a586e406b82b59e592aa20e882f0713",
                "31439a6a288a248220b910938e9b9ad4433bee235ea406568cdad8495464b33e",
                0, "请人工核对当前分析目的与证据缺口。");
        AtomicBoolean allowed = new AtomicBoolean(true);
        DevHumanRequestStore.SourceGuard requestSource = (actor, save, display, now) ->
                allowed.get() ? signed : null;
        String opinionText = "当前分析目的尚未确认，逐项专业对应方法尚无获准依据；本次不形成技术对应结论。";
        String opinionSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(opinionText.getBytes(StandardCharsets.UTF_8)));
        var opinionSigned = new DevHumanRequestStore.Source(signed.manifestId(), signed.manifestDigest(),
                opinionSha, 0, opinionText);
        DevHumanOpinionStore.SourceGuard opinionSource = (actor, save, display, now) -> opinionSigned;

        String task = tasks.create(owner, "dm8-return-task-12345", GOAL, both).taskId();
        var request = requests.create(owner, task, "dm8-return-request-12345", 1, 0,
                "REQUEST_GAP", both, requestSource);
        acceptance.accept(receiver, task, 1, "dm8-return-accept-12345", 1, 1, request.requestId(), both,
                (actor, now) -> allowed.get() ? signed : null);
        var context = new Dm8DevHumanContextStore(source, new DataSourceTransactionManager(source), zone);
        assertThat(context.read(receiver, task, 1, both, (actor, now) -> signed).messages().getFirst().text())
                .isEqualTo(GOAL);
        assertThatThrownBy(() -> context.read(owner, task, 1, both, (actor, now) -> signed))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_NOT_FOUND");
        int receipts = sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task);
        allowed.set(false);
        assertThatThrownBy(() -> context.read(receiver, task, 1, both,
                (actor, now) -> allowed.get() ? signed : null))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> first.submit(receiver, task, 1, "dm8-return-no-source", 1, 1,
                request.requestId(), both, requestSource)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_receipt WHERE task_id=?", Integer.class, task))
                .isEqualTo(receipts);
        allowed.set(true);
        List<String> outcomes;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var a = CompletableFuture.supplyAsync(() -> raceReturn(first, receiver, task, request.requestId(),
                    both, requestSource, ready, go), pool);
            var b = CompletableFuture.supplyAsync(() -> raceOpinion(opinions, receiver, task, request.requestId(),
                    both, opinionSource, ready, go), pool);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes.contains("RETURNED") || outcomes.contains("SUBMITTED")).isTrue();
        assertThatThrownBy(() -> context.read(receiver, task, 1, both, (actor, now) -> signed))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("HUMAN_REQUEST_VERSION_CONFLICT");
        assertThat(outcomes.contains("HUMAN_ALREADY_RETURNED") || outcomes.contains("HUMAN_OPINION_EXISTS"))
                .isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_return WHERE task_id=?", Integer.class, task)
                + sql.queryForObject("SELECT COUNT(*) FROM con2_human_opinion WHERE task_id=?", Integer.class, task))
                .isEqualTo(1);
        if (outcomes.contains("RETURNED")) {
            String id = restarted.read(owner, task, 1, both, requestSource).returnId();
            assertThat(first.submit(receiver, task, 1, "dm8-return-race-12345", 1, 1,
                    request.requestId(), both, requestSource).returnId()).isEqualTo(id);
            assertThat(opinions.mine(receiver, both, requestSource, opinionSource))
                    .noneMatch(item -> item.taskId().equals(task));
        } else {
            assertThatThrownBy(() -> restarted.submit(receiver, task, 1, "dm8-return-late-12345", 1, 1,
                    request.requestId(), both, requestSource)).isInstanceOf(DevTaskCoreFailure.class)
                    .hasMessage("HUMAN_OPINION_EXISTS");
        }
        var next = requests.create(owner, task, "dm8-return-next-12345", 1, 1,
                "REQUEST_GAP", both, requestSource);
        assertThat(next.requestSequence()).isEqualTo(2);
        assertThat(requests.read(owner, task, 1, both, requestSource).currentInput()).isFalse();
        assertThat(requests.current(owner, task, both, requestSource).request().currentInput()).isTrue();
        if (outcomes.contains("RETURNED")) {
            assertThat(restarted.read(owner, task, 1, both, requestSource).currentInput()).isFalse();
        } else {
            assertThat(opinions.read(owner, task, 1, both, opinionSource).currentInput()).isFalse();
        }
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), task);
        assertThat(tasks.reconcile(job, both).removed()).isGreaterThanOrEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_human_return WHERE task_id=?", Integer.class, task))
                .isZero();
    }

    private static String raceReturn(Dm8DevHumanReturnStore store, Actor human, String task, String request,
            Guard guard, DevHumanRequestStore.SourceGuard source, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try { if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Return race did not start"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        try {
            store.submit(human, task, 1, "dm8-return-race-12345", 1, 1, request, guard, source);
            return "RETURNED";
        } catch (DevTaskCoreFailure failure) { return failure.getMessage(); }
    }

    private static String raceOpinion(Dm8DevHumanOpinionStore store, Actor human, String task, String request,
            Guard guard, DevHumanOpinionStore.SourceGuard source, CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try { if (!go.await(10, TimeUnit.SECONDS)) throw new AssertionError("Opinion race did not start"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        try {
            store.submit(human, task, 1, "dm8-opinion-return-race", 1, 1, request,
                    "OPINION_GAP", guard, source);
            return "SUBMITTED";
        } catch (DevTaskCoreFailure failure) { return failure.getMessage(); }
    }
}
