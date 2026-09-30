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

abstract class Dm8DevTaskCoreSupport {
    protected final JdbcTemplate jdbc;
    protected final TransactionTemplate transactions;
    private final ZoneId sessionZone;

    protected Dm8DevTaskCoreSupport(DataSource dataSource, PlatformTransactionManager manager, ZoneId sessionZone) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(manager);
        this.sessionZone = sessionZone;
    }

    protected Attempt attempt(String id) {
        List<Attempt> result = jdbc.query("SELECT attempt_id,task_id,input_revision,cancel_epoch,"
                        + "delegated_owner_ref,authorization_version,delegation_until,status FROM con2_attempt "
                        + "WHERE attempt_id=?",
                (rs, index) -> new Attempt(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getLong(4), rs.getString(5), rs.getLong(6), instant(rs.getTimestamp(7)),
                        rs.getString(8)), id);
        return result.isEmpty() ? null : result.get(0);
    }

    protected void liveAttempt(Task task, Attempt attempt, Guard guard, Instant now) {
        if (task == null || attempt == null || !"ACTIVE".equals(task.state())
                || DevTaskExpiry.expired(task.expiresAt(), now)
                || task.revision() != attempt.revision() || task.cancelEpoch() != attempt.cancelEpoch()) {
            throw DevTaskCoreFailure.conflict("STALE_ATTEMPT");
        }
        if (!guard.delegationCurrent(attempt.owner(), task.organization(), attempt.authorizationVersion(),
                attempt.delegationUntil(), now)) throw DevTaskCoreFailure.denied();
    }

    protected static void serviceCurrent(Actor actor, Guard guard, ServiceRole role, Instant now) {
        if (actor == null || !guard.serviceCurrent(actor, role, now)) throw DevTaskCoreFailure.denied();
    }

    protected static Instant minimum(Instant... values) {
        Instant result = values[0];
        for (Instant value : values) if (value.isBefore(result)) result = value;
        return result;
    }

    protected CommandResult resolveCreateCollision(String scope, String key, String request, Actor actor, Guard guard) {
        return resolveCollision(scope, key, request, actor, guard);
    }

    protected CommandResult resolveCollision(String scope, String key, String request, Actor actor, Guard guard) {
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                currentHuman(actor, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                return replay(prior, request, actor, guard, now);
            });
        } catch (DataAccessException failure) {
            throw unavailable();
        } catch (TransactionException failure) {
            throw unavailable();
        }
    }

    protected CommandResult replay(Receipt receipt, String request, Actor actor, Guard guard, Instant now) {
        Task task = task(receipt.taskId(), false);
        readable(task, actor, guard, now);
        if (!receipt.requestSha().equals(request)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        return new CommandResult(receipt.id(), receipt.taskId(), receipt.revision(),
                DevTaskExpiry.sixCalendarMonthsAfter(receipt.committedAt()));
    }

    protected void readable(Task task, Actor actor, Guard guard, Instant now) {
        currentHuman(actor, guard, now);
        if (task == null || !task.owner().equals(actor.principal()) || !task.organization().equals(actor.organization())) {
            throw DevTaskCoreFailure.absent();
        }
        if (DevTaskExpiry.expired(task.expiresAt(), now) || "PURGING".equals(task.state())) {
            throw DevTaskCoreFailure.expired();
        }
    }

    protected static void currentHuman(Actor actor, Guard guard, Instant now) {
        if (actor == null || !guard.humanCurrent(actor, now)) throw DevTaskCoreFailure.denied();
    }

    protected Task task(String id, boolean lock) {
        List<Task> result = jdbc.query("SELECT task_id,org_ref,owner_ref,state,revision,cancel_epoch,expires_at "
                        + "FROM con2_task WHERE task_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Task(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), instant(rs.getTimestamp(7))), id);
        return result.isEmpty() ? null : result.get(0);
    }

    protected Receipt receipt(String scope, String key) {
        List<Receipt> result = jdbc.query("SELECT receipt_id,request_sha,task_id,committed_revision,committed_at "
                        + "FROM con2_receipt WHERE scope_sha=? AND idem_sha=?",
                (rs, index) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), instant(rs.getTimestamp(5))), scope, hash(key));
        return result.isEmpty() ? null : result.get(0);
    }

    protected CommandResult insertReceipt(String scope, String key, String request, String taskId,
                                        String operation, long revision, Instant now, Instant expiry) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,operation,"
                        + "committed_revision,committed_at) VALUES (?,?,?,?,?,?,?,?)",
                id, scope, hash(key), request, taskId, operation, revision, utc(now));
        return new CommandResult(id, taskId, revision, expiry);
    }

    protected void insertMessage(String taskId, long revision, String owner, String body, Instant now) {
        jdbc.update("INSERT INTO con2_message(task_id,seq,revision,actor_ref,body,body_sha,created_at) "
                        + "VALUES (?,?,?,?,?,?,?)", taskId, revision, revision, owner, body, hash(body), utc(now));
    }

    protected void insertAttempt(String taskId, long revision, long epoch, Actor actor, Instant now) {
        jdbc.update("INSERT INTO con2_attempt(attempt_id,task_id,input_revision,cancel_epoch,delegated_owner_ref,"
                        + "authorization_version,delegation_until,status,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',?)",
                UUID.randomUUID().toString(), taskId, revision, epoch, actor.principal(),
                actor.authorizationVersion(), utc(actor.credentialUntil()), utc(now));
    }

    protected void insertEvent(String taskId, long seq, String type, long revision, Instant now, String ref) {
        jdbc.update("INSERT INTO con2_event(task_id,seq,event_type,task_revision,created_at,event_ref) VALUES (?,?,?,?,?,?)",
                taskId, seq, type, revision, utc(now), ref);
    }

    protected long nextEvent(String taskId) {
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0)+1 FROM con2_event WHERE task_id=?",
                Long.class, taskId);
        return next == null ? 1 : next;
    }

    protected Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP FROM DUAL", Timestamp.class);
        if (value == null) throw unavailable();
        return value.toLocalDateTime().atZone(sessionZone).toInstant();
    }

    protected static Timestamp utc(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    protected static Instant instant(Timestamp value) {
        return value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    protected static void validateId(String id) {
        try {
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw DevTaskCoreFailure.invalid();
        } catch (IllegalArgumentException failure) {
            throw DevTaskCoreFailure.invalid();
        }
    }

    protected static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw DevTaskCoreFailure.invalid();
    }

    protected static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[] {(byte)(bytes.length >>> 24), (byte)(bytes.length >>> 16),
                        (byte)(bytes.length >>> 8), (byte)bytes.length});
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    protected static String plainHash(String field) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(field.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    protected static DevTaskCoreFailure unavailable() { return new DevTaskCoreFailure(503, "DATABASE_UNAVAILABLE"); }

    protected record Task(String id, String organization, String owner, String state, long revision,
                        long cancelEpoch, Instant expiresAt) { }
    protected record Receipt(String id, String requestSha, String taskId, long revision, Instant committedAt) { }
    protected record Attempt(String id, String taskId, long revision, long cancelEpoch, String owner,
                           long authorizationVersion, Instant delegationUntil, String status) { }
}
