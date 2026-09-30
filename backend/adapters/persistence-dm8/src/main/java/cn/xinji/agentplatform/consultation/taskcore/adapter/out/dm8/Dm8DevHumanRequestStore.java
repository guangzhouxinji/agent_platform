package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevHumanRequestStore extends Dm8DevTaskCoreSupport implements DevHumanRequestStore {
    private static final String MANIFEST = "DEV-CON-HUMAN-GAP-01";

    public Dm8DevHumanRequestStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public Request create(Actor human, String taskId, String key, long expectedTaskRevision,
                                    long expectedRequestSequence, String requestInputId,
                                    Guard guard, SourceGuard source) {
        validateId(taskId);
        validateKey(key);
        if (human == null || expectedTaskRevision < 1 || expectedRequestSequence < 0
                || !"REQUEST_GAP".equals(requestInputId)) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "HUMAN_REQUEST", taskId);
        String fingerprint = hash("HUMAN_REQUEST", Long.toString(expectedTaskRevision),
                Long.toString(expectedRequestSequence), requestInputId);
        try {
            Request committed = transactions.execute(status -> createLocked(human, taskId, key,
                    expectedTaskRevision, expectedRequestSequence, guard, source, scope, fingerprint));
            return read(human, taskId, committed.requestSequence(), guard, source);
        } catch (DataIntegrityViolationException collision) {
            try {
                return transactions.execute(status -> {
                    Instant now = databaseNow();
                    Task task = task(taskId, true);
                    readable(task, human, guard, now);
                    Receipt prior = receipt(scope, key);
                    if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                    return replay(task, human, prior, fingerprint, guard, source, now);
                });
            } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Request createLocked(Actor human, String taskId, String key, long expectedTaskRevision,
                                 long expectedRequestSequence, Guard guard, SourceGuard source,
                                 String scope, String fingerprint) {
        Instant now = databaseNow();
        Task task = task(taskId, true);
        readable(task, human, guard, now);
        Receipt prior = receipt(scope, key);
        if (prior != null) return replay(task, human, prior, fingerprint, guard, source, now);
        if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
        if (task.revision() != expectedTaskRevision) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
        long sequence = sequence(taskId);
        if (sequence != expectedRequestSequence) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
        }
        Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                        + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                        + "AND a.status='RUNNING' AND l.lease_until>?",
                rs -> rs.next() ? 1 : 0, taskId, utc(now));
        if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
        Source signed = required(human, source, true, true, now);
        long next = sequence + 1;
        String receiptId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                        + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,'HUMAN_REQUEST',?,?)",
                receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
        if (sequence == 0) {
            jdbc.update("INSERT INTO con2_human_request_state(task_id,request_seq) VALUES (?,0)", taskId);
        }
        jdbc.update("UPDATE con2_human_request_state SET request_seq=? WHERE task_id=?", next, taskId);
        jdbc.update("INSERT INTO con2_human_request(task_id,request_seq,request_id,input_revision,"
                        + "cancel_epoch,actor_ref,org_ref,auth_version,manifest_id,manifest_sha,text_sha,"
                        + "withdrawal_version,receipt_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                taskId, next, requestId, task.revision(), task.cancelEpoch(), human.principal(),
                human.organization(), human.authorizationVersion(), signed.manifestId(),
                signed.manifestDigest(), signed.textDigest(), signed.withdrawalVersion(), receiptId, utc(now));
        Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
        jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                utc(now), utc(expiry), taskId);
        insertEvent(taskId, nextEvent(taskId), "HUMAN_REQUESTED", task.revision(), now, requestId);
        Instant beforeCommit = databaseNow();
        readable(task(taskId, false), human, guard, beforeCommit);
        if (!same(signed, required(human, source, true, true, beforeCommit))) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        }
        return load(task, next, human, source, beforeCommit);
    }

    @Override public Current current(Actor human, String taskId, Guard guard, SourceGuard source) {
        validateId(taskId);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                long sequence = sequence(taskId);
                if (sequence != 0) load(task, sequence, human, source, now);
                readable(task(taskId, false), human, guard, databaseNow());
                Task latest = task(taskId, false);
                Request request = sequence == 0 ? null : load(latest, sequence, human, source, databaseNow());
                return new Current(sequence, request);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Request read(Actor human, String taskId, long sequence, Guard guard, SourceGuard source) {
        validateId(taskId);
        if (sequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                load(task, sequence, human, source, now);
                readable(task(taskId, false), human, guard, databaseNow());
                return load(task(taskId, false), sequence, human, source, databaseNow());
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Request replay(Task task, Actor human, Receipt prior, String fingerprint,
                           Guard guard, SourceGuard source, Instant now) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        List<Long> sequences = jdbc.query("SELECT request_seq FROM con2_human_request "
                        + "WHERE task_id=? AND receipt_id=?", (rs, index) -> rs.getLong(1), task.id(), prior.id());
        if (sequences.size() != 1) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_INCONSISTENT");
        Request request = load(task, sequences.getFirst(), human, source, now);
        readable(task(task.id(), false), human, guard, databaseNow());
        return request;
    }

    private Request load(Task task, long sequence, Actor human, SourceGuard source, Instant now) {
        List<Stored> rows = jdbc.query("SELECT request_id,input_revision,cancel_epoch,actor_ref,org_ref,"
                        + "manifest_id,manifest_sha,text_sha,withdrawal_version,created_at "
                        + "FROM con2_human_request WHERE task_id=? AND request_seq=?",
                (rs, index) -> new Stored(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getLong(9), instant(rs.getTimestamp(10))), task.id(), sequence);
        if (rows.size() != 1) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_INCONSISTENT");
        Stored row = rows.getFirst();
        Source signed = required(human, source, false, true, now);
        if (!MANIFEST.equals(row.manifestId()) || !row.manifestId().equals(signed.manifestId())
                || !row.manifestSha().equals(signed.manifestDigest())
                || !row.textSha().equals(signed.textDigest())
                || row.withdrawalVersion() != signed.withdrawalVersion()
                || !row.owner().equals(task.owner()) || !row.organization().equals(task.organization())) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        }
        boolean current = "ACTIVE".equals(task.state()) && task.revision() == row.revision()
                && task.cancelEpoch() == row.cancelEpoch() && sequence(task.id()) == sequence;
        List<Acceptance> acceptance = acceptanceInstalled() ? jdbc.query(
                "SELECT accepted_by,accepted_at FROM con2_human_acceptance WHERE task_id=? AND request_seq=?",
                (rs, index) -> new Acceptance(rs.getString(1), instant(rs.getTimestamp(2))), task.id(), sequence)
                : List.of();
        Acceptance accepted = acceptance.isEmpty() ? null : acceptance.getFirst();
        return new Request(task.id(), row.id(), sequence, row.revision(), row.owner(),
                "REQUEST_GAP", signed.text(), row.createdAt(), current, task.state(),
                accepted == null ? "NOT_ACCEPTED" : "ACCEPTED",
                accepted == null ? null : accepted.actor(), accepted == null ? null : accepted.at());
    }

    private boolean acceptanceInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_ACCEPTANCE", null)) {
                return tables.next();
            }
        }));
    }

    private long sequence(String taskId) {
        List<Long> rows = jdbc.query("SELECT request_seq FROM con2_human_request_state WHERE task_id=?",
                (rs, index) -> rs.getLong(1), taskId);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private static Source required(Actor human, SourceGuard source, boolean save, boolean display, Instant now) {
        Source signed = source.permitted(human, save, display, now);
        if (signed == null) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        return signed;
    }

    private static boolean same(Source left, Source right) {
        return left.manifestId().equals(right.manifestId())
                && left.manifestDigest().equals(right.manifestDigest())
                && left.textDigest().equals(right.textDigest())
                && left.text().equals(right.text())
                && left.withdrawalVersion() == right.withdrawalVersion();
    }

    private record Stored(String id, long revision, long cancelEpoch, String owner, String organization,
                          String manifestId, String manifestSha, String textSha,
                          long withdrawalVersion, Instant createdAt) { }
    private record Acceptance(String actor, Instant at) { }
}
