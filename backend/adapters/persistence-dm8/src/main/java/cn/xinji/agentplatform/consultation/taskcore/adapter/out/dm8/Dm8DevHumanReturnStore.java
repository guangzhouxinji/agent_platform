package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanReturnStore;
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

public final class Dm8DevHumanReturnStore extends Dm8DevTaskCoreSupport implements DevHumanReturnStore {
    public Dm8DevHumanReturnStore(DataSource dataSource, PlatformTransactionManager manager, ZoneId zone) {
        super(dataSource, manager, zone);
    }

    @Override public Returned submit(Actor human, String taskId, long sequence, String key,
            long expectedTaskRevision, long expectedRequestSequence, String requestId, Guard guard,
            DevHumanRequestStore.SourceGuard source) {
        validateId(taskId); validateId(requestId); validateKey(key);
        if (human == null || sequence < 1 || expectedTaskRevision < 1 || sequence != expectedRequestSequence) {
            throw DevTaskCoreFailure.invalid();
        }
        String scope = hash(human.organization(), human.principal(), "HUMAN_RETURN", taskId,
                Long.toString(sequence));
        String fingerprint = hash("HUMAN_RETURN", requestId, Long.toString(expectedTaskRevision),
                Long.toString(expectedRequestSequence));
        try {
            transactions.execute(status -> {
                Task task = task(taskId, true);
                Instant now = databaseNow();
                Accepted accepted = authorized(task, human, sequence, guard, source, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) { replay(task, accepted, prior, fingerprint, human, source); return null; }
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedTaskRevision || accepted.revision() != task.revision()
                        || accepted.epoch() != task.cancelEpoch()) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                if (currentSequence(taskId) != sequence || !accepted.id().equals(requestId)) {
                    throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
                }
                if (returned(taskId, sequence) != null) throw DevTaskCoreFailure.conflict("HUMAN_ALREADY_RETURNED");
                if (exists("con2_human_opinion", taskId, sequence)) {
                    throw DevTaskCoreFailure.conflict("HUMAN_OPINION_EXISTS");
                }
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                                + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                                + "AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                String receiptId = UUID.randomUUID().toString();
                String returnId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                                + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,'HUMAN_RETURN',?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
                jdbc.update("INSERT INTO con2_human_return(task_id,request_seq,request_id,return_id,receipt_id,"
                                + "input_revision,cancel_epoch,actor_ref,org_ref,auth_version,returned_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, sequence, requestId, returnId, receiptId, task.revision(), task.cancelEpoch(),
                        human.principal(), human.organization(), human.authorizationVersion(), utc(now));
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(DevTaskExpiry.sixCalendarMonthsAfter(now)), taskId);
                insertEvent(taskId, nextEvent(taskId), "HUMAN_RETURNED", task.revision(), now, returnId);
                authorized(task(taskId, false), human, sequence, guard, source, databaseNow());
                return null;
            });
            return receiverRead(human, taskId, sequence, guard, source);
        } catch (DataIntegrityViolationException collision) {
            try {
                return transactions.execute(status -> {
                    Task task = task(taskId, true);
                    Accepted accepted = authorized(task, human, sequence, guard, source, databaseNow());
                    Receipt prior = receipt(scope, key);
                    if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                    return replay(task, accepted, prior, fingerprint, human, source);
                });
            } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Returned read(Actor owner, String taskId, long sequence, Guard guard,
            DevHumanRequestStore.SourceGuard source) {
        validateId(taskId);
        if (sequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Task task = task(taskId, true);
                readable(task, owner, guard, databaseNow());
                Row row = returned(taskId, sequence);
                if (row == null) throw DevTaskCoreFailure.absent();
                sourceMatches(task, row.requestId(), sequence, owner, source, false, databaseNow());
                readable(task(taskId, false), owner, guard, databaseNow());
                sourceMatches(task, row.requestId(), sequence, owner, source, false, databaseNow());
                return project(task, row);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Returned receiverRead(Actor human, String id, long sequence, Guard guard,
            DevHumanRequestStore.SourceGuard source) {
        return transactions.execute(status -> {
            Task task = task(id, true);
            Accepted accepted = authorized(task, human, sequence, guard, source, databaseNow());
            Row row = returned(id, sequence);
            if (row == null || !row.actor().equals(human.principal()) || !row.requestId().equals(accepted.id())
                    || !project(task, row).currentInput() || currentSequence(id) != sequence) {
                throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
            }
            authorized(task(id, false), human, sequence, guard, source, databaseNow());
            return project(task, row);
        });
    }

    private Returned replay(Task task, Accepted accepted, Receipt prior, String fingerprint, Actor human,
            DevHumanRequestStore.SourceGuard source) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        Row row = returned(task.id(), accepted.sequence());
        if (row == null || !prior.id().equals(row.receiptId()) || !row.actor().equals(human.principal())
                || !"ACTIVE".equals(task.state()) || !project(task, row).currentInput()
                || currentSequence(task.id()) != accepted.sequence()) {
            throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
        }
        sourceMatches(task, row.requestId(), accepted.sequence(), human, source, true, databaseNow());
        return project(task, row);
    }

    private Accepted authorized(Task task, Actor human, long sequence, Guard guard,
            DevHumanRequestStore.SourceGuard source, Instant now) {
        currentHuman(human, guard, now);
        if (task == null || !task.organization().equals(human.organization())) throw DevTaskCoreFailure.absent();
        if (!now.isBefore(task.expiresAt()) || "PURGING".equals(task.state())) throw DevTaskCoreFailure.expired();
        List<Accepted> rows = jdbc.query("SELECT a.request_id,r.input_revision,r.cancel_epoch,a.accepted_by "
                        + "FROM con2_human_acceptance a JOIN con2_human_request r ON "
                        + "r.task_id=a.task_id AND r.request_seq=a.request_seq "
                        + "WHERE a.task_id=? AND a.request_seq=?",
                (rs, index) -> new Accepted(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), sequence), task.id(), sequence);
        if (rows.isEmpty() || !human.principal().equals(rows.getFirst().actor())) {
            throw DevTaskCoreFailure.absent();
        }
        sourceMatches(task, rows.getFirst().id(), sequence, human, source, true, now);
        return rows.getFirst();
    }

    private void sourceMatches(Task task, String requestId, long sequence, Actor actor,
            DevHumanRequestStore.SourceGuard guard, boolean save, Instant now) {
        DevHumanRequestStore.Source signed = guard.permitted(actor, save, true, now);
        if (signed == null) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        List<String[]> rows = jdbc.query("SELECT request_id,org_ref,manifest_id,manifest_sha,text_sha,"
                        + "withdrawal_version FROM con2_human_request WHERE task_id=? AND request_seq=?",
                (rs, index) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), Long.toString(rs.getLong(6))}, task.id(), sequence);
        if (rows.size() != 1 || !requestId.equals(rows.getFirst()[0])
                || !task.organization().equals(rows.getFirst()[1])
                || !signed.manifestId().equals(rows.getFirst()[2])
                || !signed.manifestDigest().equals(rows.getFirst()[3])
                || !signed.textDigest().equals(rows.getFirst()[4])
                || signed.withdrawalVersion() != Long.parseLong(rows.getFirst()[5])) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        }
    }

    private boolean exists(String table, String id, long sequence) {
        Integer found = jdbc.query("SELECT 1 FROM " + table + " WHERE task_id=? AND request_seq=?",
                rs -> rs.next() ? 1 : 0, id, sequence);
        return found != null && found != 0;
    }

    private long currentSequence(String id) {
        List<Long> rows = jdbc.query("SELECT request_seq FROM con2_human_request_state WHERE task_id=?",
                (rs, index) -> rs.getLong(1), id);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private Row returned(String id, long sequence) {
        List<Row> rows = jdbc.query("SELECT request_id,return_id,receipt_id,input_revision,cancel_epoch,"
                        + "actor_ref,org_ref,returned_at FROM con2_human_return WHERE task_id=? AND request_seq=?",
                (rs, index) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                        instant(rs.getTimestamp(8)), sequence), id, sequence);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Returned project(Task task, Row row) {
        if (!task.organization().equals(row.organization())) throw DevTaskCoreFailure.absent();
        return new Returned(task.id(), row.requestId(), row.sequence(), row.revision(), row.id(), row.actor(),
                row.at(), "ACTIVE".equals(task.state()) && task.revision() == row.revision()
                        && task.cancelEpoch() == row.epoch() && currentSequence(task.id()) == row.sequence());
    }

    private record Accepted(String id, long revision, long epoch, String actor, long sequence) { }
    private record Row(String requestId, String id, String receiptId, long revision, long epoch,
                       String actor, String organization, Instant at, long sequence) { }
}
