package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore.SourceGuard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevTextResultStore;
import cn.xinji.agentplatform.security.v2.DevComparisonInputDm8Support;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

final class DevInputDisplayDm8Scenarios {
    private DevInputDisplayDm8Scenarios() { }

    static void run(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, Actor job, String taskId, String setId, Guard guard, SourceGuard sources,
            JdbcTemplate sql, int receipts, int events, String goal, String supplement) throws Exception {
        oldSaveCannotDisplay(tasks, results, human, taskId, setId, guard, sources, sql, receipts, events);
        withdrawBeforeReturn(tasks, results, human, taskId, setId, guard, sources, sql, receipts, events);
        signedFileWithdrawalDuringRead(tasks, results, human, taskId, setId, guard, sources,
                sql, receipts, events);
        cancellationOrder(tasks, results, human, job, guard, sources, sql, goal, supplement);
    }

    static void oldSaveCannotDisplay(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, String taskId, String setId, Guard saveGuard, SourceGuard sources,
            JdbcTemplate sql, int receipts, int events) {
        Guard saveWithoutDisplay = displayGuard(saveGuard, () -> false);
        assertThatThrownBy(() -> tasks.read(human, taskId, saveWithoutDisplay))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThatThrownBy(() -> results.prepareEvidence(human, taskId, setId, 2,
                saveWithoutDisplay, sources)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        assertNoWrites(sql, taskId, receipts, events);
    }

    static void withdrawBeforeReturn(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, String taskId, String setId, Guard saveGuard, SourceGuard sources,
            JdbcTemplate sql, int receipts, int events) {
        AtomicInteger taskChecks = new AtomicInteger();
        Guard taskGuard = displayGuard(saveGuard, () -> taskChecks.getAndIncrement() == 0);
        assertThatThrownBy(() -> tasks.read(human, taskId, taskGuard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("SOURCE_UNAVAILABLE");
        assertThat(taskChecks.get()).isEqualTo(2);
        AtomicInteger evidenceChecks = new AtomicInteger();
        Guard evidenceGuard = displayGuard(saveGuard, () -> evidenceChecks.getAndIncrement() == 0);
        assertThatThrownBy(() -> results.prepareEvidence(human, taskId, setId, 2,
                evidenceGuard, sources)).isInstanceOf(DevTaskCoreFailure.class)
                .hasMessage("SOURCE_UNAVAILABLE");
        assertThat(evidenceChecks.get()).isEqualTo(2);
        assertNoWrites(sql, taskId, receipts, events);
    }

    static void signedFileWithdrawalDuringRead(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, String taskId, String setId, Guard saveGuard, SourceGuard sources,
            JdbcTemplate sql, int receipts, int events) throws Exception {
        Path signed = WorkspaceTestPaths.root().resolve(Path.of("data", "consultation",
                "dev-comparison-input-v1"));
        Path temporary = Files.createTempDirectory("con2-display-withdraw-");
        Path manifest = temporary.resolve("manifest.json");
        try {
            Files.copy(signed.resolve("manifest.json"), manifest);
            Files.copy(signed.resolve("input.json"), temporary.resolve("input.json"));
            Guard current = DevComparisonInputDm8Support.displayGuard(saveGuard, manifest);
            assertThat(tasks.read(human, taskId, current).messages().getFirst().body()).isNotBlank();
            withdrawBetweenChecks(paused -> tasks.read(human, taskId, paused), current, manifest);
            assertNoWrites(sql, taskId, receipts, events);
            Files.copy(signed.resolve("manifest.json"), manifest, StandardCopyOption.REPLACE_EXISTING);
            assertThat(results.prepareEvidence(human, taskId, setId, 2, current, sources).targetText()).isNotBlank();
            withdrawBetweenChecks(paused -> results.prepareEvidence(human, taskId, setId, 2,
                    paused, sources), current, manifest);
            assertNoWrites(sql, taskId, receipts, events);
            Files.copy(signed.resolve("manifest.json"), manifest, StandardCopyOption.REPLACE_EXISTING);
            assertThat(tasks.read(human, taskId, current).messages().getFirst().body()).isNotBlank();
        } finally {
            Files.deleteIfExists(temporary.resolve("input.json"));
            Files.deleteIfExists(manifest);
            Files.deleteIfExists(temporary);
        }
    }

    private static void withdrawBetweenChecks(java.util.function.Function<Guard, ?> read,
            Guard current, Path manifest) throws Exception {
        CountDownLatch firstDisplay = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Guard paused = new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return current.humanCurrent(actor, now);
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return current.inputAllowed(actor, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                boolean allowed = current.inputDisplayAllowed(actor, kind, text, now);
                if (checks.getAndIncrement() != 0) return allowed;
                firstDisplay.countDown();
                try {
                    return allowReturn.await(10, TimeUnit.SECONDS) && allowed;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            @Override public boolean delegationCurrent(String owner, String organization, long version,
                                                       Instant until, Instant now) {
                return current.delegationCurrent(owner, organization, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return current.serviceCurrent(actor, role, now);
            }
        };
        var pending = CompletableFuture.supplyAsync(() -> read.apply(paused));
        try {
            assertThat(firstDisplay.await(10, TimeUnit.SECONDS)).isTrue();
            Files.writeString(manifest, Files.readString(manifest).replace("\"withdrawalVersion\": 0",
                    "\"withdrawalVersion\": 1"));
            allowReturn.countDown();
            assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(DevTaskCoreFailure.class)
                    .hasRootCauseMessage("SOURCE_UNAVAILABLE");
            assertThat(checks.get()).isEqualTo(2);
        } finally {
            allowReturn.countDown();
            pending.handle((ignored, failure) -> null).get(10, TimeUnit.SECONDS);
        }
    }

    static void cancellationOrder(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, Actor job, Guard guard, SourceGuard sources, JdbcTemplate sql,
            String goal, String supplement) {
        String beforeTask = tasks.create(human, "display-cancel-before-create", goal, guard).taskId();
        String beforeSet = results.search(human, beforeTask, "display-cancel-before-search", 1, 0,
                guard, sources).setId();
        tasks.cancel(human, beforeTask, "display-cancel-before-command", 1, guard);
        int beforeReceipts = count(sql, "con2_receipt", beforeTask);
        int beforeEvents = count(sql, "con2_event", beforeTask);
        assertThatThrownBy(() -> results.prepareEvidence(human, beforeTask, beforeSet, 2,
                guard, sources)).isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_CLOSED");
        assertThat(tasks.read(human, beforeTask, guard).messages().getFirst().body()).isEqualTo(goal);
        assertNoWrites(sql, beforeTask, beforeReceipts, beforeEvents);

        String afterTask = tasks.create(human, "display-cancel-after-create", goal, guard).taskId();
        String afterSet = results.search(human, afterTask, "display-cancel-after-search", 1, 0,
                guard, sources).setId();
        int afterReceipts = count(sql, "con2_receipt", afterTask);
        int afterEvents = count(sql, "con2_event", afterTask);
        assertThat(results.prepareEvidence(human, afterTask, afterSet, 2, guard, sources).targetText())
                .isEqualTo(goal);
        assertNoWrites(sql, afterTask, afterReceipts, afterEvents);
        tasks.cancel(human, afterTask, "display-cancel-after-command", 1, guard);
        assertThatThrownBy(() -> results.prepareEvidence(human, afterTask, afterSet, 2,
                guard, sources)).isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_CLOSED");
        assertNoWrites(sql, afterTask, afterReceipts + 1, afterEvents + 1);

        Timestamp expired = Timestamp.valueOf(LocalDateTime.ofInstant(
                Instant.now().minusSeconds(1), ZoneOffset.UTC));
        sql.update("UPDATE con2_task SET expires_at=? WHERE task_id IN (?,?)",
                expired, beforeTask, afterTask);
        int expiredCount = sql.queryForObject("SELECT COUNT(*) FROM con2_task "
                + "WHERE expires_at <= CURRENT_TIMESTAMP", Integer.class);
        assertThat(tasks.reconcile(job, guard).removed()).isEqualTo(expiredCount);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM con2_task WHERE task_id IN (?,?)",
                Integer.class, beforeTask, afterTask)).isZero();
        cancellationDuringRead(tasks, human, job, guard, sql, goal);
        cancellationDuringEvidence(tasks, results, human, job, guard, sources, sql, goal);
        supplementDuringRead(tasks, human, job, guard, sql, goal, supplement);
    }

    private static void supplementDuringRead(Dm8DevTaskCoreStore tasks, Actor human, Actor job,
            Guard guard, JdbcTemplate sql, String goal, String supplement) {
        String taskId = tasks.create(human, "display-append-race-create", goal, guard).taskId();
        CountDownLatch firstDisplay = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Guard paused = displayGuard(guard, () -> {
            if (checks.getAndIncrement() != 0) return true;
            firstDisplay.countDown();
            try {
                return allowReturn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        var read = CompletableFuture.supplyAsync(() -> tasks.read(human, taskId, paused));
        try {
            assertThat(firstDisplay.await(10, TimeUnit.SECONDS)).isTrue();
            tasks.append(human, taskId, "display-append-race-command", 1, supplement, guard);
            allowReturn.countDown();
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(DevTaskCoreFailure.class)
                    .hasRootCauseMessage("VERSION_CONFLICT");
            var current = tasks.read(human, taskId, guard);
            assertThat(current.revision()).isEqualTo(2);
            assertThat(current.messages()).extracting("body").containsExactly(goal, supplement);
            assertThat(checks.get()).isEqualTo(1);
        } catch (Exception failure) {
            throw new AssertionError("Supplement/read interleaving failed", failure);
        } finally {
            allowReturn.countDown();
            sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), taskId);
            tasks.reconcile(job, guard);
        }
    }

    private static void cancellationDuringEvidence(Dm8DevTaskCoreStore tasks, Dm8DevTextResultStore results,
            Actor human, Actor job, Guard guard, SourceGuard sources, JdbcTemplate sql, String goal) {
        String taskId = tasks.create(human, "display-evidence-race-create", goal, guard).taskId();
        String setId = results.search(human, taskId, "display-evidence-race-search", 1, 0,
                guard, sources).setId();
        int receipts = count(sql, "con2_receipt", taskId);
        int events = count(sql, "con2_event", taskId);
        CountDownLatch firstDisplay = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        Guard paused = displayGuard(guard, () -> {
            firstDisplay.countDown();
            try {
                return allowReturn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        var preparation = CompletableFuture.supplyAsync(() -> results.prepareEvidence(
                human, taskId, setId, 2, paused, sources));
        CompletableFuture<?> cancellation = null;
        try {
            assertThat(firstDisplay.await(10, TimeUnit.SECONDS)).isTrue();
            cancellation = CompletableFuture.runAsync(() -> {
                cancelStarted.countDown();
                tasks.cancel(human, taskId, "display-evidence-race-cancel", 1, guard);
            });
            assertThat(cancelStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.isDone()).isFalse();
            allowReturn.countDown();
            assertThat(preparation.get(10, TimeUnit.SECONDS).targetText()).isEqualTo(goal);
            cancellation.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> results.prepareEvidence(human, taskId, setId, 2,
                    guard, sources)).isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_CLOSED");
            assertNoWrites(sql, taskId, receipts + 1, events + 1);
        } catch (Exception failure) {
            throw new AssertionError("Evidence/cancellation interleaving failed", failure);
        } finally {
            allowReturn.countDown();
            if (cancellation != null) cancellation.join();
            sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), taskId);
            tasks.reconcile(job, guard);
        }
    }

    private static void cancellationDuringRead(Dm8DevTaskCoreStore tasks, Actor human, Actor job,
            Guard guard, JdbcTemplate sql, String goal) {
        String taskId = tasks.create(human, "display-cancel-race-create", goal, guard).taskId();
        CountDownLatch firstDisplay = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Guard paused = displayGuard(guard, () -> {
            if (checks.getAndIncrement() != 0) return true;
            firstDisplay.countDown();
            try {
                return allowReturn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        var read = CompletableFuture.supplyAsync(() -> tasks.read(human, taskId, paused));
        try {
            assertThat(firstDisplay.await(10, TimeUnit.SECONDS)).isTrue();
            tasks.cancel(human, taskId, "display-cancel-race-command", 1, guard);
            allowReturn.countDown();
            assertThatThrownBy(() -> read.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(DevTaskCoreFailure.class)
                    .hasRootCauseMessage("VERSION_CONFLICT");
            var historical = tasks.read(human, taskId, guard);
            assertThat(historical.state()).isEqualTo("CANCELLED");
            assertThat(historical.messages().getFirst().body()).isEqualTo(goal);
            assertThat(checks.get()).isEqualTo(1);
        } catch (Exception failure) {
            throw new AssertionError("Cancellation/read interleaving failed", failure);
        } finally {
            allowReturn.countDown();
            sql.update("UPDATE con2_task SET expires_at=? WHERE task_id=?",
                    Timestamp.valueOf(LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC)), taskId);
            tasks.reconcile(job, guard);
        }
    }

    private static Guard displayGuard(Guard saveGuard, BooleanSupplier displayCurrent) {
        return new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return saveGuard.humanCurrent(actor, now);
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return saveGuard.inputAllowed(actor, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return displayCurrent.getAsBoolean();
            }
            @Override public boolean delegationCurrent(String owner, String organization, long version,
                                                       Instant until, Instant now) {
                return saveGuard.delegationCurrent(owner, organization, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return saveGuard.serviceCurrent(actor, role, now);
            }
        };
    }

    private static void assertNoWrites(JdbcTemplate sql, String taskId, int receipts, int events) {
        assertThat(count(sql, "con2_receipt", taskId)).isEqualTo(receipts);
        assertThat(count(sql, "con2_event", taskId)).isEqualTo(events);
    }

    private static int count(JdbcTemplate sql, String table, String taskId) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id=?", Integer.class, taskId);
    }
}
