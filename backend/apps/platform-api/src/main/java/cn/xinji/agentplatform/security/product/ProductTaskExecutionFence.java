package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal task execution slot; its caller must establish current service authorization. */
final class ProductTaskExecutionFence {
    record Lease(String attemptId, long token, Instant until) { }
    static final class ReconciliationRequired extends IllegalStateException {
        ReconciliationRequired() { super("Expired execution requires external reconciliation"); }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ProductTaskExecutionFence(JdbcTemplate db, TransactionTemplate transactions, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.clock = clock;
    }

    Lease acquire(String taskId, String inputId, long expectedVersion, String worker, Duration duration) {
        if (blank(taskId) || blank(inputId) || blank(worker) || worker.length() > 200
                || expectedVersion < 1 || duration == null || duration.isNegative() || duration.isZero())
            throw new IllegalArgumentException("Exact task, input, worker and lease duration required");
        Lease acquired = transactions.execute(status -> {
            Task task = task(taskId);
            currentInput(task, inputId, expectedVersion);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            List<Slot> slots = slot(taskId);
            if (!slots.isEmpty()) {
                Slot previous = slots.getFirst();
                if ("ACTIVE".equals(previous.status()) && !previous.until().isAfter(now)) {
                    db.update("UPDATE con3_execution_lease SET status='RECONCILE_REQUIRED' WHERE task_id=? "
                            + "AND status='ACTIVE' AND fencing_token=?", taskId, previous.token());
                    return null;
                }
                throw new IllegalStateException("Existing execution requires completion or reconciliation");
            }
            Instant until = now.plus(duration);
            String attempt = UUID.randomUUID().toString();
            db.update("INSERT INTO con3_execution_lease VALUES(?,?,?,?,?,1,?,'ACTIVE',?,?)",
                    taskId, attempt, inputId, task.version(), task.epoch(), worker,
                    Timestamp.from(until), Timestamp.from(now));
            return new Lease(attempt, 1, until);
        });
        if (acquired == null) throw new ReconciliationRequired();
        return acquired;
    }

    void checkpoint(String taskId, String inputId, Lease lease, long sequence, String resultRef, String resultSha) {
        if (lease == null || sequence < 1 || blank(resultRef) || resultRef.length() > 300
                || resultSha == null || !resultSha.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Committed checkpoint reference and digest required");
        transactions.executeWithoutResult(status -> {
            requireCurrent(taskId, inputId, lease);
            db.update("INSERT INTO con3_execution_checkpoint VALUES(?,?,?,?,?,?,?)", taskId,
                    lease.attemptId(), lease.token(), sequence, resultRef, resultSha,
                    Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        });
    }

    void beginExternalDispatch(String taskId, String inputId, Lease lease, String operationId) {
        if (blank(operationId) || operationId.length() > 100)
            throw new IllegalArgumentException("External operation identity required");
        transactions.executeWithoutResult(status -> {
            requireCurrent(taskId, inputId, lease);
            db.update("INSERT INTO con3_execution_dispatch VALUES(?,?,?,?, 'INTENT',?)",
                    taskId, lease.attemptId(), lease.token(), operationId,
                    Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        });
    }

    void assertOwner(String taskId, Lease lease, String worker) {
        if (lease == null || blank(worker)) throw new IllegalStateException("Worker lease required");
        List<String> owners = db.query("SELECT worker_ref FROM con3_execution_lease "
                        + "WHERE task_id=? AND attempt_id=? AND fencing_token=? AND status='ACTIVE'",
                (rs, row) -> rs.getString(1), taskId, lease.attemptId(), lease.token());
        if (owners.size() != 1 || !worker.equals(owners.getFirst()))
            throw new IllegalStateException("Worker does not own execution lease");
    }

    Lease reconcileAuthorized(String taskId, String inputId, String delegationId,
            ProductWorkerAccess access, jakarta.servlet.http.HttpServletRequest request, Duration duration) {
        if (access == null || request == null || duration == null || duration.isNegative()
                || duration.isZero()) throw new IllegalArgumentException("Current worker proof required");
        return transactions.execute(status -> {
            var authorized = access.require(request, taskId, inputId, delegationId);
            return reconcileNoDispatch(taskId, inputId, authorized, duration);
        });
    }

    Lease reconcileNoDispatch(String taskId, String inputId,
            ProductWorkerAccess.Authorized authorized, Duration duration) {
        Task task = task(taskId);
        currentInput(task, inputId, authorized.taskVersion());
        Slot old = slot(taskId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Execution slot missing"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!taskId.equals(authorized.taskId()) || !inputId.equals(authorized.inputId())
                || !"RECONCILE_REQUIRED".equals(old.status()) || old.until().isAfter(now)
                || !inputId.equals(old.inputId()) || old.version() != task.version()
                || old.epoch() != task.epoch() || old.token() == Long.MAX_VALUE)
            throw new IllegalStateException("Attempt cannot be reconciled");
        Integer dispatched = db.queryForObject("SELECT COUNT(*) FROM con3_execution_dispatch "
                + "WHERE task_id=? AND attempt_id=? AND fencing_token=?", Integer.class,
                taskId, old.attempt(), old.token());
        if (dispatched != 0) throw new IllegalStateException("External outcome requires authoritative reconciliation");
        List<Checkpoint> checkpoints = db.query("SELECT sequence_no,result_ref,result_sha "
                        + "FROM con3_execution_checkpoint WHERE task_id=? AND attempt_id=? "
                        + "AND fencing_token=? ORDER BY sequence_no DESC",
                (rs, row) -> new Checkpoint(rs.getLong(1), rs.getString(2), rs.getString(3)),
                taskId, old.attempt(), old.token());
        Checkpoint last = checkpoints.isEmpty() ? new Checkpoint(0, null, null) : checkpoints.getFirst();
        String nextAttempt = UUID.randomUUID().toString();
        long nextToken = old.token() + 1;
        Instant until = now.plus(duration);
        db.update("INSERT INTO con3_execution_reconciliation VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                taskId, old.attempt(), old.token(), nextAttempt, nextToken, inputId,
                last.sequence(), last.ref(), last.sha(), authorized.serviceSubject(), Timestamp.from(now));
        if (db.update("UPDATE con3_execution_lease SET attempt_id=?,fencing_token=?,worker_ref=?,"
                        + "status='ACTIVE',lease_until=?,updated_at=? WHERE task_id=? AND attempt_id=? "
                        + "AND fencing_token=? AND status='RECONCILE_REQUIRED'",
                nextAttempt, nextToken, authorized.serviceSubject(), Timestamp.from(until),
                Timestamp.from(now), taskId, old.attempt(), old.token()) != 1)
            throw new IllegalStateException("Execution slot changed");
        return new Lease(nextAttempt, nextToken, until);
    }

    void publish(String taskId, String inputId, Lease lease, Runnable sameDatabaseCommit) {
        if (sameDatabaseCommit == null) throw new IllegalArgumentException("Result transaction required");
        transactions.executeWithoutResult(status -> {
            requireCurrent(taskId, inputId, lease);
            Integer pending = db.queryForObject("SELECT COUNT(*) FROM con3_execution_dispatch "
                            + "WHERE task_id=? AND attempt_id=? AND fencing_token=? AND status='INTENT'",
                    Integer.class, taskId, lease.attemptId(), lease.token());
            if (pending != 0) throw new IllegalStateException("External outcome is not reconciled");
            sameDatabaseCommit.run();
            if (db.update("UPDATE con3_execution_lease SET status='COMMITTED' "
                    + "WHERE task_id=? AND attempt_id=? AND fencing_token=? AND status='ACTIVE'",
                    taskId, lease.attemptId(), lease.token()) != 1)
                throw new IllegalStateException("Execution lost its slot");
        });
    }

    private void requireCurrent(String taskId, String inputId, Lease lease) {
        if (lease == null || !lease.until().isAfter(clock.instant()))
            throw new IllegalStateException("Execution lease expired");
        Task task = task(taskId);
        currentInput(task, inputId, task.version());
        Slot current = slot(taskId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Execution slot missing"));
        if (!"ACTIVE".equals(current.status()) || !current.attempt().equals(lease.attemptId())
                || current.token() != lease.token() || !current.inputId().equals(inputId)
                || current.version() != task.version() || current.epoch() != task.epoch()
                || !current.until().isAfter(clock.instant()))
            throw new IllegalStateException("Stale execution token or task version");
    }

    private Task task(String taskId) {
        return db.query("SELECT version,cancel_epoch,state,expires_at FROM con3_task "
                        + "WHERE task_id=? FOR UPDATE",
                (rs, row) -> new Task(taskId, rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), taskId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Task missing"));
    }

    private void currentInput(Task task, String inputId, long expectedVersion) {
        if (!"IN_PROGRESS".equals(task.state()) || task.version() != expectedVersion
                || !task.expiresAt().isAfter(clock.instant()))
            throw new IllegalStateException("Task no longer executable");
        Integer count = db.queryForObject("SELECT COUNT(*) FROM con3_goal_current c "
                        + "JOIN con3_search_input i ON i.task_id=c.task_id AND i.goal_id=c.goal_id "
                        + "AND i.goal_revision=c.revision AND i.task_version=c.task_version "
                        + "WHERE c.task_id=? AND i.input_id=? AND i.expires_at>?",
                Integer.class, task.id(), inputId, Timestamp.from(clock.instant()));
        if (count != 1) throw new IllegalStateException("Current confirmed input missing");
    }

    private List<Slot> slot(String taskId) {
        return db.query("SELECT attempt_id,input_id,task_version,cancel_epoch,fencing_token,status,lease_until "
                        + "FROM con3_execution_lease WHERE task_id=? FOR UPDATE",
                (rs, row) -> new Slot(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant()), taskId);
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
    private record Task(String id, long version, long epoch, String state, Instant expiresAt) { }
    private record Slot(String attempt, String inputId, long version, long epoch, long token,
                        String status, Instant until) { }
    private record Checkpoint(long sequence, String ref, String sha) { }
}
