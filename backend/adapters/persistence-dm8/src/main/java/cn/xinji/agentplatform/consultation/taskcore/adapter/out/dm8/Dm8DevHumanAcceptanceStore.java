package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanAcceptanceStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevHumanAcceptanceStore extends Dm8DevTaskCoreSupport implements DevHumanAcceptanceStore {
    private static final String MANIFEST = "DEV-CON-HUMAN-GAP-01";

    public Dm8DevHumanAcceptanceStore(DataSource dataSource, PlatformTransactionManager manager, ZoneId zone) {
        super(dataSource, manager, zone);
    }

    @Override public List<Pending> pending(Actor human, Guard guard, SourceGuard source) {
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                currentHuman(human, guard, now);
                DevHumanRequestStore.Source signed = required(human, source, now);
                List<String> candidates = jdbc.query(connection -> {
                    var statement = connection.prepareStatement("SELECT t.task_id FROM con2_task t "
                            + "JOIN con2_human_request_state s ON s.task_id=t.task_id "
                            + "WHERE t.org_ref=? AND t.state='ACTIVE' AND t.expires_at>? "
                            + "AND NOT EXISTS (SELECT 1 FROM con2_human_acceptance a "
                            + "WHERE a.task_id=t.task_id AND a.request_seq=s.request_seq) ORDER BY t.task_id");
                    statement.setString(1, human.organization());
                    statement.setTimestamp(2, utc(now));
                    statement.setMaxRows(101);
                    return statement;
                }, (rs, index) -> rs.getString(1));
                if (candidates.size() > 100) throw DevTaskCoreFailure.conflict("QUEUE_LIMIT");
                List<Pending> result = new ArrayList<>();
                for (String id : candidates) {
                    Task task = task(id, true);
                    if (task == null || !"ACTIVE".equals(task.state())
                            || !task.organization().equals(human.organization())
                            || !now.isBefore(task.expiresAt())) continue;
                    long sequence = sequence(id);
                    Stored request = stored(id, sequence);
                    if (request == null || request.revision() != task.revision()
                            || request.cancelEpoch() != task.cancelEpoch() || accepted(id, sequence) != null) continue;
                    sourceMatches(request, signed, task);
                    result.add(new Pending(id, request.id(), sequence, request.revision(), request.createdAt()));
                }
                currentHuman(human, guard, databaseNow());
                if (!same(signed, required(human, source, databaseNow()))) {
                    throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
                }
                result.sort(Comparator.comparing(Pending::createdAt).thenComparing(Pending::taskId));
                return List.copyOf(result);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Accepted accept(Actor human, String taskId, long sequence, String key,
                                      long expectedTaskRevision, long expectedRequestSequence, String requestId,
                                      Guard guard, SourceGuard source) {
        validateId(taskId);
        validateId(requestId);
        validateKey(key);
        if (human == null || sequence < 1 || expectedTaskRevision < 1 || expectedRequestSequence < 1
                || sequence != expectedRequestSequence) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "HUMAN_ACCEPT", taskId,
                Long.toString(sequence));
        String fingerprint = hash("HUMAN_ACCEPT", requestId, Long.toString(expectedTaskRevision),
                Long.toString(expectedRequestSequence));
        try {
            transactions.execute(status -> {
                acceptLocked(human, taskId, sequence, key, expectedTaskRevision, requestId,
                        guard, source, scope, fingerprint);
                return null;
            });
            return readAccepted(human, taskId, sequence, guard, source);
        } catch (DataIntegrityViolationException collision) {
            try {
                return transactions.execute(status -> {
                    Task task = task(taskId, true);
                    Instant now = databaseNow();
                    authorized(task, human, guard, source, stored(taskId, sequence), now);
                    Receipt prior = receipt(scope, key);
                    if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                    return replay(task, human, sequence, prior, fingerprint, guard, source);
                });
            } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private void acceptLocked(Actor human, String taskId, long sequence, String key,
                              long expectedTaskRevision, String requestId, Guard guard, SourceGuard source,
                              String scope, String fingerprint) {
        Instant now = databaseNow();
        Task task = task(taskId, true);
        Stored request = stored(taskId, sequence);
        DevHumanRequestStore.Source signed = authorized(task, human, guard, source, request, now);
        Receipt prior = receipt(scope, key);
        if (prior != null) { replay(task, human, sequence, prior, fingerprint, guard, source); return; }
        if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
        if (task.revision() != expectedTaskRevision || request.revision() != task.revision()
                || request.cancelEpoch() != task.cancelEpoch()) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
        if (sequence(taskId) != sequence) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
        if (!request.id().equals(requestId)) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
        if (accepted(taskId, sequence) != null) throw DevTaskCoreFailure.conflict("HUMAN_ALREADY_ACCEPTED");
        Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a ON a.attempt_id=l.attempt_id "
                        + "WHERE l.task_id=? AND a.status='RUNNING' AND l.lease_until>?",
                rs -> rs.next() ? 1 : 0, taskId, utc(now));
        if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
        String receiptId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                        + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,'HUMAN_ACCEPT',?,?)",
                receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
        jdbc.update("INSERT INTO con2_human_acceptance(task_id,request_seq,request_id,receipt_id,"
                        + "accepted_by,org_ref,auth_version,accepted_at) VALUES (?,?,?,?,?,?,?,?)",
                taskId, sequence, requestId, receiptId, human.principal(), human.organization(),
                human.authorizationVersion(), utc(now));
        jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                utc(now), utc(DevTaskExpiry.sixCalendarMonthsAfter(now)), taskId);
        insertEvent(taskId, nextEvent(taskId), "HUMAN_ACCEPTED", task.revision(), now, requestId);
        Task latest = task(taskId, false);
        currentHuman(human, guard, databaseNow());
        if (!"ACTIVE".equals(latest.state()) || !same(signed, required(human, source, databaseNow()))) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        }
    }

    private Accepted readAccepted(Actor human, String taskId, long sequence,
                                  Guard guard, SourceGuard source) {
        return transactions.execute(status -> {
            Task task = task(taskId, true);
            Instant now = databaseNow();
            Stored request = stored(taskId, sequence);
            DevHumanRequestStore.Source signed = authorized(task, human, guard, source, request, now);
            Accepted accepted = accepted(taskId, sequence);
            if (accepted == null || !human.principal().equals(accepted.acceptedBy())) {
                throw DevTaskCoreFailure.conflict("HUMAN_ALREADY_ACCEPTED");
            }
            if (!"ACTIVE".equals(task.state()) || task.revision() != request.revision()
                    || task.cancelEpoch() != request.cancelEpoch()) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
            currentHuman(human, guard, databaseNow());
            if (!same(signed, required(human, source, databaseNow()))) {
                throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
            }
            return accepted;
        });
    }

    private Accepted replay(Task task, Actor human, long sequence, Receipt prior,
                            String fingerprint, Guard guard, SourceGuard source) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        Accepted result = accepted(task.id(), sequence);
        if (result == null || !human.principal().equals(result.acceptedBy())
                || !"ACTIVE".equals(task.state()) || task.revision() != result.inputRevision()
                || sequence(task.id()) != sequence) throw DevTaskCoreFailure.conflict("HUMAN_ALREADY_ACCEPTED");
        currentHuman(human, guard, databaseNow());
        required(human, source, databaseNow());
        return result;
    }

    private DevHumanRequestStore.Source authorized(Task task, Actor human, Guard guard, SourceGuard source,
                                                    Stored request, Instant now) {
        currentHuman(human, guard, now);
        if (task == null || request == null || !task.organization().equals(human.organization())) {
            throw DevTaskCoreFailure.absent();
        }
        if (!now.isBefore(task.expiresAt()) || "PURGING".equals(task.state())) throw DevTaskCoreFailure.expired();
        DevHumanRequestStore.Source signed = required(human, source, now);
        sourceMatches(request, signed, task);
        return signed;
    }

    private void sourceMatches(Stored request, DevHumanRequestStore.Source signed, Task task) {
        if (!MANIFEST.equals(request.manifest()) || !request.manifestSha().equals(signed.manifestDigest())
                || !request.textSha().equals(signed.textDigest())
                || request.withdrawalVersion() != signed.withdrawalVersion()
                || !request.organization().equals(task.organization()) || !request.owner().equals(task.owner())) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        }
    }

    private long sequence(String taskId) {
        List<Long> result = jdbc.query("SELECT request_seq FROM con2_human_request_state WHERE task_id=?",
                (rs, index) -> rs.getLong(1), taskId);
        return result.isEmpty() ? 0 : result.getFirst();
    }

    private Stored stored(String taskId, long sequence) {
        List<Stored> result = jdbc.query("SELECT request_id,input_revision,cancel_epoch,actor_ref,org_ref,"
                        + "manifest_id,manifest_sha,text_sha,withdrawal_version,created_at "
                        + "FROM con2_human_request WHERE task_id=? AND request_seq=?",
                (rs, index) -> new Stored(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9),
                        instant(rs.getTimestamp(10))), taskId, sequence);
        return result.isEmpty() ? null : result.getFirst();
    }

    private Accepted accepted(String taskId, long sequence) {
        List<Accepted> result = jdbc.query("SELECT a.request_id,r.input_revision,a.accepted_by,a.accepted_at "
                        + "FROM con2_human_acceptance a JOIN con2_human_request r "
                        + "ON r.task_id=a.task_id AND r.request_seq=a.request_seq "
                        + "WHERE a.task_id=? AND a.request_seq=?",
                (rs, index) -> new Accepted(taskId, rs.getString(1), sequence, rs.getLong(2),
                        rs.getString(3), instant(rs.getTimestamp(4)), true), taskId, sequence);
        return result.isEmpty() ? null : result.getFirst();
    }

    private static DevHumanRequestStore.Source required(Actor human, SourceGuard source, Instant now) {
        DevHumanRequestStore.Source signed = source.permitted(human, now);
        if (signed == null) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        return signed;
    }

    private static boolean same(DevHumanRequestStore.Source left, DevHumanRequestStore.Source right) {
        return left.manifestId().equals(right.manifestId()) && left.manifestDigest().equals(right.manifestDigest())
                && left.textDigest().equals(right.textDigest()) && left.withdrawalVersion() == right.withdrawalVersion()
                && left.text().equals(right.text());
    }

    private record Stored(String id, long revision, long cancelEpoch, String owner, String organization,
                          String manifest, String manifestSha, String textSha,
                          long withdrawalVersion, Instant createdAt) { }
}
