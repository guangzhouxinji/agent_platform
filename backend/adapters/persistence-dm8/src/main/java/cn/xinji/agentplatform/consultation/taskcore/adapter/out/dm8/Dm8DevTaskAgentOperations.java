package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.*;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

final class Dm8DevTaskAgentOperations extends Dm8DevTaskCoreSupport {
    Dm8DevTaskAgentOperations(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    public Lease lease(Actor agent, String attemptId, Guard guard) {
        validateId(attemptId);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                serviceCurrent(agent, guard, ServiceRole.AGENT, now);
                Attempt first = attempt(attemptId);
                if (first == null) throw DevTaskCoreFailure.absent();
                Task task = task(first.taskId(), true);
                Attempt attempt = attempt(attemptId);
                liveAttempt(task, attempt, guard, now);
                if ("CHECKPOINTED".equals(attempt.status()) || "STOPPED".equals(attempt.status())) {
                    throw DevTaskCoreFailure.conflict("ATTEMPT_CLOSED");
                }
                List<Lease> existing = jdbc.query("SELECT attempt_id,fencing_token,lease_until "
                                + "FROM con2_lease WHERE task_id=?",
                        (rs, index) -> new Lease(rs.getString(1), task.id(), rs.getLong(2),
                                instant(rs.getTimestamp(3))), task.id());
                Lease current = existing.isEmpty() ? null : existing.get(0);
                if (current != null && attemptId.equals(current.attemptId())
                        && now.isBefore(current.leaseUntil())) {
                    throw DevTaskCoreFailure.conflict("TASK_BUSY");
                }
                long token = current == null ? 1 : current.fencingToken() + 1;
                if (token <= 0) throw DevTaskCoreFailure.conflict("TOKEN_EXHAUSTED");
                Instant until = minimum(now.plusSeconds(30), task.expiresAt(), attempt.delegationUntil(),
                        agent.credentialUntil());
                if (!now.isBefore(until)) throw DevTaskCoreFailure.denied();
                if (current == null) {
                    jdbc.update("INSERT INTO con2_lease(task_id,attempt_id,fencing_token,lease_until) "
                            + "VALUES (?,?,?,?)", task.id(), attemptId, token, utc(until));
                } else {
                    jdbc.update("UPDATE con2_lease SET attempt_id=?,fencing_token=?,lease_until=? WHERE task_id=?",
                            attemptId, token, utc(until), task.id());
                }
                jdbc.update("UPDATE con2_attempt SET status='RUNNING' WHERE attempt_id=?", attemptId);
                Instant beforeCommit = databaseNow();
                serviceCurrent(agent, guard, ServiceRole.AGENT, beforeCommit);
                liveAttempt(task, attempt, guard, beforeCommit);
                if (!beforeCommit.isBefore(until)) throw DevTaskCoreFailure.conflict("LEASE_EXPIRED");
                return new Lease(attemptId, task.id(), token, until);
            });
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    public Checkpoint checkpoint(Actor agent, String attemptId, long token,
                                            String committedRef, Guard guard) {
        validateId(attemptId);
        if (token <= 0 || committedRef == null || committedRef.length() > 100) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                serviceCurrent(agent, guard, ServiceRole.AGENT, now);
                Attempt first = attempt(attemptId);
                if (first == null) throw DevTaskCoreFailure.absent();
                Task task = task(first.taskId(), true);
                Attempt attempt = attempt(attemptId);
                liveAttempt(task, attempt, guard, now);
                if (!"RUNNING".equals(attempt.status())
                        || !committedRef.equals("message:" + attempt.revision())) {
                    throw DevTaskCoreFailure.conflict("STALE_ATTEMPT");
                }
                List<Lease> leases = jdbc.query("SELECT attempt_id,fencing_token,lease_until "
                                + "FROM con2_lease WHERE task_id=?",
                        (rs, index) -> new Lease(rs.getString(1), task.id(), rs.getLong(2),
                                instant(rs.getTimestamp(3))), task.id());
                if (leases.size() != 1 || !attemptId.equals(leases.get(0).attemptId())
                        || token != leases.get(0).fencingToken() || !now.isBefore(leases.get(0).leaseUntil())) {
                    throw DevTaskCoreFailure.conflict("STALE_TOKEN");
                }
                Integer message = jdbc.query("SELECT 1 FROM con2_message WHERE task_id=? AND revision=?",
                        rs -> rs.next() ? 1 : 0, task.id(), attempt.revision());
                if (message == null || message == 0) throw DevTaskCoreFailure.conflict("UNKNOWN_COMMITTED_REF");
                Long seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM con2_checkpoint "
                        + "WHERE attempt_id=?", Long.class, attemptId);
                long next = seq == null ? 1 : seq;
                jdbc.update("INSERT INTO con2_checkpoint(attempt_id,seq,task_id,task_revision,cancel_epoch,"
                                + "fencing_token,committed_ref,created_at) VALUES (?,?,?,?,?,?,?,?)",
                        attemptId, next, task.id(), task.revision(), task.cancelEpoch(), token, committedRef, utc(now));
                jdbc.update("UPDATE con2_attempt SET status='CHECKPOINTED' WHERE attempt_id=?", attemptId);
                insertEvent(task.id(), nextEvent(task.id()), "CHECKPOINTED", task.revision(), now, attemptId);
                Instant beforeCommit = databaseNow();
                serviceCurrent(agent, guard, ServiceRole.AGENT, beforeCommit);
                liveAttempt(task, attempt, guard, beforeCommit);
                if (!beforeCommit.isBefore(leases.get(0).leaseUntil())) {
                    throw DevTaskCoreFailure.conflict("STALE_TOKEN");
                }
                return new Checkpoint(attemptId, next, task.revision(), committedRef, now);
            });
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    public ReconcileResult reconcile(Actor job, Guard guard) {
        try {
            Instant now = transactions.execute(status -> {
                Instant current = databaseNow();
                serviceCurrent(job, guard, ServiceRole.JOB, current);
                return current;
            });
            List<String> candidates = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT task_id FROM con2_task "
                        + "WHERE expires_at<=? OR state='PURGING' ORDER BY expires_at,task_id");
                statement.setTimestamp(1, utc(now));
                statement.setMaxRows(20);
                return statement;
            }, (rs, index) -> rs.getString(1));
            int removed = 0, failed = 0;
            for (String id : candidates) {
                try {
                    boolean marked = Boolean.TRUE.equals(transactions.execute(status -> {
                        Instant current = databaseNow();
                        serviceCurrent(job, guard, ServiceRole.JOB, current);
                        Task task = task(id, true);
                        if (task == null || ("PURGING".equals(task.state())
                                ? false : current.isBefore(task.expiresAt()))) return false;
                        jdbc.update("UPDATE con2_task SET state='PURGING' WHERE task_id=?", id);
                        serviceCurrent(job, guard, ServiceRole.JOB, databaseNow());
                        return true;
                    }));
                    if (!marked) continue;
                    Boolean deleted = transactions.execute(status -> {
                        serviceCurrent(job, guard, ServiceRole.JOB, databaseNow());
                        Task task = task(id, true);
                        if (task == null || !"PURGING".equals(task.state())) return false;
                        if (purposeSelectionsInstalled()) {
                            jdbc.update("DELETE FROM con2_purpose_revision WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_purpose_state WHERE task_id=?", id);
                        }
                        if (textResultsInstalled()) {
                            jdbc.update("DELETE FROM con2_focus WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_result_member WHERE set_id IN "
                                    + "(SELECT set_id FROM con2_result_set WHERE task_id=?)", id);
                            jdbc.update("DELETE FROM con2_result_set WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_result_state WHERE task_id=?", id);
                        }
                        if (featureConfirmationsInstalled()) {
                            jdbc.update("DELETE FROM con2_feature_member WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_feature_revision WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_feature_state WHERE task_id=?", id);
                        }
                        if (humanRequestsInstalled()) {
                            if (humanAcceptanceInstalled()) {
                                if (humanReturnInstalled()) {
                                    jdbc.update("DELETE FROM con2_human_return WHERE task_id=?", id);
                                }
                                if (humanOpinionInstalled()) {
                                    jdbc.update("DELETE FROM con2_human_opinion WHERE task_id=?", id);
                                }
                                jdbc.update("DELETE FROM con2_human_acceptance WHERE task_id=?", id);
                            }
                            jdbc.update("DELETE FROM con2_human_request WHERE task_id=?", id);
                            jdbc.update("DELETE FROM con2_human_request_state WHERE task_id=?", id);
                        }
                        if (correctionsInstalled()) {
                            jdbc.update("DELETE FROM con2_correction WHERE task_id=?", id);
                        }
                        jdbc.update("DELETE FROM con2_checkpoint WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_event WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_lease WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_attempt WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_message WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_receipt WHERE task_id=?", id);
                        jdbc.update("DELETE FROM con2_task WHERE task_id=?", id);
                        serviceCurrent(job, guard, ServiceRole.JOB, databaseNow());
                        return true;
                    });
                    if (Boolean.TRUE.equals(deleted)) removed++;
                } catch (DataAccessException | TransactionException databaseFailure) {
                    failed++;
                }
            }
            return new ReconcileResult(removed, failed);
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    private boolean purposeSelectionsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_PURPOSE_STATE", null)) {
                return tables.next();
            }
        }));
    }

    private boolean textResultsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_RESULT_STATE", null)) {
                return tables.next();
            }
        }));
    }

    private boolean featureConfirmationsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_FEATURE_STATE", null)) {
                return tables.next();
            }
        }));
    }

    private boolean humanRequestsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_REQUEST_STATE", null)) {
                return tables.next();
            }
        }));
    }

    private boolean humanAcceptanceInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_ACCEPTANCE", null)) {
                return tables.next();
            }
        }));
    }

    private boolean humanOpinionInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_OPINION", null)) {
                return tables.next();
            }
        }));
    }

    private boolean humanReturnInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_RETURN", null)) {
                return tables.next();
            }
        }));
    }

    private boolean correctionsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_CORRECTION", null)) {
                return tables.next();
            }
        }));
    }

}
