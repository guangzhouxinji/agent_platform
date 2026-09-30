package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8DevTaskCoreStore extends Dm8DevTaskCoreSupport implements DevTaskCoreStore {
    public Dm8DevTaskCoreStore(DataSource dataSource, PlatformTransactionManager manager, ZoneId sessionZone) {
        super(dataSource, manager, sessionZone);
        this.agentOperations = new Dm8DevTaskAgentOperations(dataSource, manager, sessionZone);
    }

    @Override public CommandResult create(Actor human, String key, String goal, Guard guard) {
        validateKey(key);
        String scope = hash(human.organization(), human.principal(), "CREATE", "CREATE");
        String request = hash("CREATE", goal);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                currentHuman(human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) return replay(prior, request, human, guard, now);
                if (!guard.inputAllowed(human, "GOAL", goal, now)) throw DevTaskCoreFailure.invalid();
                String taskId = UUID.randomUUID().toString();
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("INSERT INTO con2_task(task_id,org_ref,owner_ref,state,revision,cancel_epoch,"
                                + "last_business_change_at,expires_at) VALUES (?,?,?,'ACTIVE',1,0,?,?)",
                        taskId, human.organization(), human.principal(), utc(now), utc(expiry));
                insertMessage(taskId, 1, human.principal(), goal, now);
                insertAttempt(taskId, 1, 0, human, now);
                insertEvent(taskId, 1, "CREATED", 1, now, null);
                Instant beforeCommit = databaseNow();
                currentHuman(human, guard, beforeCommit);
                if (!guard.inputAllowed(human, "GOAL", goal, beforeCommit)) throw DevTaskCoreFailure.denied();
                return insertReceipt(scope, key, request, taskId, "CREATE", 1, now, expiry);
            });
        } catch (DataIntegrityViolationException collision) {
            return resolveCreateCollision(scope, key, request, human, guard);
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    @Override public CommandResult append(Actor human, String taskId, String key, long expectedVersion,
                                           String message, Guard guard) {
        validateKey(key);
        validateId(taskId);
        if (expectedVersion < 1) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "APPEND", taskId);
        String request = hash("APPEND", Long.toString(expectedVersion), message);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) return replay(prior, request, human, guard, now);
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (!guard.inputAllowed(human, "SUPPLEMENT", message, now)) throw DevTaskCoreFailure.invalid();
                if (task.revision() != expectedVersion) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a ON a.attempt_id=l.attempt_id "
                                + "WHERE l.task_id=? AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                long revision = task.revision() + 1;
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("UPDATE con2_attempt SET status='STOPPED' WHERE task_id=? AND status<>'STOPPED'",
                        taskId);
                jdbc.update("UPDATE con2_task SET revision=?,last_business_change_at=?,expires_at=? WHERE task_id=?",
                        revision, utc(now), utc(expiry), taskId);
                insertMessage(taskId, revision, human.principal(), message, now);
                insertAttempt(taskId, revision, task.cancelEpoch(), human, now);
                insertEvent(taskId, nextEvent(taskId), "MESSAGE_ACCEPTED", revision, now, null);
                Instant beforeCommit = databaseNow();
                currentHuman(human, guard, beforeCommit);
                if (!guard.inputAllowed(human, "SUPPLEMENT", message, beforeCommit)) {
                    throw DevTaskCoreFailure.denied();
                }
                return insertReceipt(scope, key, request, taskId, "APPEND", revision, now, expiry);
            });
        } catch (DataIntegrityViolationException collision) {
            return resolveCollision(scope, key, request, human, guard);
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    @Override public TaskView read(Actor human, String taskId, Guard guard) {
        validateId(taskId);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, false);
                readable(task, human, guard, now);
                List<Message> messages = jdbc.query("SELECT seq,revision,body,created_at FROM con2_message "
                        + "WHERE task_id=? ORDER BY seq", (rs, index) -> new Message(rs.getLong(1),
                        rs.getLong(2), rs.getString(3), instant(rs.getTimestamp(4))), taskId);
                Map<Long, String> messageHashes = jdbc.query("SELECT revision,body_sha FROM con2_message "
                        + "WHERE task_id=?", (rs, index) -> Map.entry(rs.getLong(1), rs.getString(2)), taskId)
                        .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                List<StoredCorrection> stored = correctionsInstalled() ? jdbc.query(
                        "SELECT old_revision,new_revision,utterance,utterance_sha,new_sha,created_at,manifest_sha,old_sha "
                                + "FROM con2_correction WHERE task_id=? ORDER BY new_revision",
                        (rs, index) -> new StoredCorrection(rs.getLong(1), rs.getLong(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), instant(rs.getTimestamp(6)),
                                rs.getString(7), rs.getString(8)), taskId)
                        : List.of();
                Map<Long, StoredCorrection> byRevision = stored.stream()
                        .collect(Collectors.toMap(StoredCorrection::newRevision, Function.identity()));
                verifyMessages(messages, messageHashes, byRevision, human, guard, now);
                List<Checkpoint> points = jdbc.query("SELECT c.attempt_id,c.seq,c.task_revision,c.committed_ref,"
                                + "c.created_at FROM con2_checkpoint c WHERE c.task_id=? ORDER BY c.created_at,c.seq",
                        (rs, index) -> new Checkpoint(rs.getString(1), rs.getLong(2), rs.getLong(3),
                                rs.getString(4), instant(rs.getTimestamp(5))), taskId);
                Instant latest = databaseNow();
                Task current = task(taskId, false);
                readable(current, human, guard, latest);
                if (current.revision() != task.revision() || current.cancelEpoch() != task.cancelEpoch()
                        || !current.state().equals(task.state())) {
                    throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                }
                verifyMessages(messages, messageHashes, byRevision, human, guard, latest);
                return new TaskView(current.id(), current.state(), current.revision(),
                        current.cancelEpoch(), current.expiresAt(),
                        messages, points, stored.stream().map(item -> new Correction(item.oldRevision(),
                                item.newRevision(), item.utterance(), item.createdAt())).toList());
            });
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    @Override public CommandResult cancel(Actor human, String taskId, String key, long expectedVersion,
                                           Guard guard) {
        validateId(taskId);
        validateKey(key);
        if (expectedVersion < 1) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "CANCEL", taskId);
        String request = hash("CANCEL", Long.toString(expectedVersion));
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) return replay(prior, request, human, guard, now);
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedVersion) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                long revision = task.revision() + 1;
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("UPDATE con2_task SET state='CANCELLED',revision=?,cancel_epoch=?,"
                                + "terminated_at=?,last_business_change_at=?,expires_at=? WHERE task_id=?",
                        revision, task.cancelEpoch() + 1, utc(now), utc(now), utc(expiry), taskId);
                jdbc.update("UPDATE con2_attempt SET status='STOPPED' WHERE task_id=?", taskId);
                jdbc.update("DELETE FROM con2_lease WHERE task_id=?", taskId);
                insertEvent(taskId, nextEvent(taskId), "CANCELLED", revision, now, null);
                currentHuman(human, guard, databaseNow());
                return insertReceipt(scope, key, request, taskId, "CANCEL", revision, now, expiry);
            });
        } catch (DataIntegrityViolationException collision) {
            return resolveCollision(scope, key, request, human, guard);
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    private final Dm8DevTaskAgentOperations agentOperations;

    private void verifyMessages(List<Message> messages, Map<Long, String> messageHashes,
                                Map<Long, StoredCorrection> corrections,
                                Actor human, Guard guard, Instant now) {
        if (messages.size() != messageHashes.size()
                || messages.size() > 2 && (!corrections.containsKey(3L) || corrections.size() != 1)) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        for (Message message : messages) {
            if (message.sequence() != message.revision()
                    || !hash(message.body()).equals(messageHashes.get(message.revision()))) {
                throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
            }
            StoredCorrection correction = corrections.get(message.revision());
            if (correction != null) {
                if (correction.oldRevision() != 2 || correction.newRevision() != 3
                        || messages.stream().noneMatch(old -> old.revision() == correction.oldRevision()
                                && plainHash(old.body()).equals(correction.oldSha()))
                        || !plainHash(message.body()).equals(correction.newSha())
                        || !plainHash(correction.utterance()).equals(correction.utteranceSha())
                        || !guard.correctionAllowed(human, "MANIFEST_SHA", correction.manifestSha(), now)
                        || !guard.correctionAllowed(human, "OLD_SHA", correction.oldSha(), now)
                        || !guard.correctionAllowed(human, "NEW_TEXT", message.body(), now)
                        || !guard.correctionAllowed(human, "UTTERANCE", correction.utterance(), now)) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
            } else {
                String kind = message.sequence() == 1 ? "GOAL" : "SUPPLEMENT";
                if (!guard.inputAllowed(human, kind, message.body(), now)
                        || !guard.inputDisplayAllowed(human, kind, message.body(), now)) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
            }
        }
    }

    private boolean correctionsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_CORRECTION", null)) {
                return tables.next();
            }
        }));
    }

    private record StoredCorrection(long oldRevision, long newRevision, String utterance,
                                    String utteranceSha, String newSha, Instant createdAt,
                                    String manifestSha, String oldSha) { }

    @Override public Lease lease(Actor agent, String attemptId, Guard guard) {
        return agentOperations.lease(agent, attemptId, guard);
    }

    @Override public Checkpoint checkpoint(Actor agent, String attemptId, long token,
                                            String committedRef, Guard guard) {
        return agentOperations.checkpoint(agent, attemptId, token, committedRef, guard);
    }

    @Override public ReconcileResult reconcile(Actor job, Guard guard) {
        return agentOperations.reconcile(job, guard);
    }
}
