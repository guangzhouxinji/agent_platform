package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanOpinionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevHumanOpinionStore extends Dm8DevTaskCoreSupport implements DevHumanOpinionStore {
    private static final String MANIFEST = "DEV-CON-HUMAN-GAP-01";

    public Dm8DevHumanOpinionStore(DataSource dataSource, PlatformTransactionManager manager, ZoneId zone) {
        super(dataSource, manager, zone);
    }

    @Override public List<Pending> mine(Actor human, Guard guard,
            DevHumanRequestStore.SourceGuard requestSource, SourceGuard opinionSource) {
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                currentHuman(human, guard, now);
                DevHumanRequestStore.Source requestSigned = requestRequired(human, requestSource, now);
                DevHumanRequestStore.Source opinionSigned = required(human, opinionSource, true, true, now);
                List<String> ids = jdbc.query(connection -> {
                    var statement = connection.prepareStatement("SELECT a.task_id FROM con2_human_acceptance a "
                            + "JOIN con2_task t ON t.task_id=a.task_id "
                            + "JOIN con2_human_request_state s ON s.task_id=a.task_id AND s.request_seq=a.request_seq "
                            + "JOIN con2_human_request r ON r.task_id=a.task_id AND r.request_seq=a.request_seq "
                            + "WHERE a.accepted_by=? AND a.org_ref=? AND t.state='ACTIVE' AND t.expires_at>? "
                            + "AND t.revision=r.input_revision AND t.cancel_epoch=r.cancel_epoch "
                            + "AND NOT EXISTS (SELECT 1 FROM con2_human_opinion o WHERE o.task_id=a.task_id "
                            + "AND o.request_seq=a.request_seq) "
                            + (returnsInstalled() ? "AND NOT EXISTS (SELECT 1 FROM con2_human_return h "
                                    + "WHERE h.task_id=a.task_id AND h.request_seq=a.request_seq) " : "")
                            + "ORDER BY a.task_id");
                    statement.setString(1, human.principal()); statement.setString(2, human.organization());
                    statement.setTimestamp(3, utc(now)); statement.setMaxRows(101);
                    return statement;
                }, (rs, index) -> rs.getString(1));
                if (ids.size() > 100) throw DevTaskCoreFailure.conflict("QUEUE_LIMIT");
                List<Pending> result = new ArrayList<>();
                for (String id : ids) {
                    Task task = task(id, true);
                    long sequence = currentSequence(id);
                    Accepted accepted = authorized(task, human, sequence, guard, databaseNow());
                    if (!"ACTIVE".equals(task.state()) || task.revision() != accepted.revision()
                            || task.cancelEpoch() != accepted.epoch() || row(id, sequence) != null
                            || returned(id, sequence)) {
                        throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
                    }
                    List<String> sources = jdbc.query("SELECT manifest_sha,text_sha,withdrawal_version "
                                    + "FROM con2_human_request WHERE task_id=? AND request_seq=?",
                            (rs, index) -> List.of(rs.getString(1), rs.getString(2),
                                    Long.toString(rs.getLong(3))), id, sequence).stream().findFirst().orElse(null);
                    if (sources == null || !sources.get(0).equals(requestSigned.manifestDigest())
                            || !sources.get(1).equals(requestSigned.textDigest())
                            || !sources.get(2).equals(Long.toString(requestSigned.withdrawalVersion()))) {
                        throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
                    }
                    result.add(new Pending(id, accepted.id(), sequence, accepted.revision(), accepted.at()));
                }
                currentHuman(human, guard, databaseNow());
                if (!same(requestSigned, requestRequired(human, requestSource, databaseNow()))
                        || !same(opinionSigned, required(human, opinionSource, true, true, databaseNow()))) {
                    throw DevTaskCoreFailure.conflict("HUMAN_OPINION_SOURCE_UNAVAILABLE");
                }
                result.sort(Comparator.comparing(Pending::acceptedAt).thenComparing(Pending::taskId));
                return List.copyOf(result);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Opinion submit(Actor human, String taskId, long sequence, String key,
            long expectedTaskRevision, long expectedRequestSequence, String requestId, String opinionInputId,
            Guard guard, SourceGuard source) {
        validateId(taskId); validateId(requestId); validateKey(key);
        if (human == null || sequence < 1 || expectedTaskRevision < 1 || expectedRequestSequence != sequence
                || !"OPINION_GAP".equals(opinionInputId)) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "HUMAN_OPINION", taskId,
                Long.toString(sequence));
        String fingerprint = hash("HUMAN_OPINION", requestId, Long.toString(expectedTaskRevision),
                Long.toString(expectedRequestSequence), opinionInputId);
        try {
            transactions.execute(status -> {
                Task task = task(taskId, true);
                Instant now = databaseNow();
                Accepted accepted = authorized(task, human, sequence, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) { replay(task, accepted, prior, fingerprint, human, source); return null; }
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedTaskRevision || accepted.revision() != task.revision()
                        || accepted.epoch() != task.cancelEpoch()) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                if (currentSequence(taskId) != sequence || !accepted.id().equals(requestId)) {
                    throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
                }
                if (row(taskId, sequence) != null) throw DevTaskCoreFailure.conflict("HUMAN_OPINION_EXISTS");
                if (returned(taskId, sequence)) throw DevTaskCoreFailure.conflict("HUMAN_ALREADY_RETURNED");
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                                + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                                + "AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                DevHumanRequestStore.Source signed = required(human, source, true, true, now);
                String opinionId = UUID.randomUUID().toString();
                String receiptId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                                + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,'HUMAN_OPINION',?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
                jdbc.update("INSERT INTO con2_human_opinion(task_id,request_seq,request_id,opinion_id,receipt_id,"
                                + "opinion_input_id,input_revision,cancel_epoch,actor_ref,org_ref,auth_version,"
                                + "manifest_id,manifest_sha,text_sha,withdrawal_version,submitted_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, sequence, requestId, opinionId, receiptId, opinionInputId, task.revision(),
                        task.cancelEpoch(), human.principal(), human.organization(), human.authorizationVersion(),
                        signed.manifestId(), signed.manifestDigest(), signed.textDigest(),
                        signed.withdrawalVersion(), utc(now));
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(DevTaskExpiry.sixCalendarMonthsAfter(now)), taskId);
                insertEvent(taskId, nextEvent(taskId), "HUMAN_OPINION_SUBMITTED", task.revision(), now, opinionId);
                currentHuman(human, guard, databaseNow());
                if (!same(signed, required(human, source, true, true, databaseNow()))) {
                    throw DevTaskCoreFailure.conflict("HUMAN_OPINION_SOURCE_UNAVAILABLE");
                }
                return null;
            });
            return receiverRead(human, taskId, sequence, guard, source);
        } catch (DataIntegrityViolationException collision) {
            try {
                return transactions.execute(status -> {
                    Task task = task(taskId, true);
                    Accepted accepted = authorized(task, human, sequence, guard, databaseNow());
                    Receipt prior = receipt(scope, key);
                    if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                    return replay(task, accepted, prior, fingerprint, human, source);
                });
            } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Opinion read(Actor owner, String taskId, long sequence, Guard guard, SourceGuard source) {
        validateId(taskId);
        if (sequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Task task = task(taskId, true);
                readable(task, owner, guard, databaseNow());
                Opinion result = project(task, row(taskId, sequence), owner, source, databaseNow());
                readable(task(taskId, false), owner, guard, databaseNow());
                return project(task(taskId, false), row(taskId, sequence), owner, source, databaseNow());
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Opinion receiverRead(Actor human, String taskId, long sequence, Guard guard, SourceGuard source) {
        return transactions.execute(status -> {
            Task task = task(taskId, true);
            authorized(task, human, sequence, guard, databaseNow());
            Opinion result = project(task, row(taskId, sequence), human, source, databaseNow());
            if (!result.currentInput()) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
            currentHuman(human, guard, databaseNow());
            return project(task(taskId, false), row(taskId, sequence), human, source, databaseNow());
        });
    }

    private Opinion replay(Task task, Accepted accepted, Receipt prior, String fingerprint, Actor human,
                           SourceGuard source) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        Row row = row(task.id(), accepted.sequence());
        if (row == null || !"ACTIVE".equals(task.state()) || task.revision() != row.revision()
                || task.cancelEpoch() != row.epoch() || currentSequence(task.id()) != accepted.sequence()
                || !prior.id().equals(row.receiptId())) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
        required(human, source, true, true, databaseNow());
        return project(task, row, human, source, databaseNow());
    }

    private Accepted authorized(Task task, Actor human, long sequence, Guard guard, Instant now) {
        currentHuman(human, guard, now);
        if (task == null || !task.organization().equals(human.organization())) throw DevTaskCoreFailure.absent();
        if (!now.isBefore(task.expiresAt()) || "PURGING".equals(task.state())) throw DevTaskCoreFailure.expired();
        List<Accepted> rows = jdbc.query("SELECT a.request_id,r.input_revision,r.cancel_epoch,a.accepted_by,"
                        + "a.request_seq,a.accepted_at FROM con2_human_acceptance a JOIN con2_human_request r "
                        + "ON r.task_id=a.task_id AND r.request_seq=a.request_seq "
                        + "WHERE a.task_id=? AND a.request_seq=?",
                (rs, index) -> new Accepted(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getLong(5), instant(rs.getTimestamp(6))), task.id(), sequence);
        if (rows.isEmpty() || !human.principal().equals(rows.getFirst().actor())) {
            throw DevTaskCoreFailure.absent();
        }
        return rows.getFirst();
    }

    private Opinion project(Task task, Row row, Actor actor, SourceGuard source, Instant now) {
        if (row == null) throw DevTaskCoreFailure.absent();
        DevHumanRequestStore.Source signed = required(actor, source, false, true, now);
        if (!MANIFEST.equals(row.manifest()) || !signed.manifestDigest().equals(row.manifestSha())
                || !signed.textDigest().equals(row.textSha()) || signed.withdrawalVersion() != row.withdrawal()
                || !row.organization().equals(task.organization())) {
            throw DevTaskCoreFailure.conflict("HUMAN_OPINION_SOURCE_UNAVAILABLE");
        }
        boolean current = "ACTIVE".equals(task.state()) && row.revision() == task.revision()
                && row.epoch() == task.cancelEpoch() && row.sequence() == currentSequence(task.id());
        return new Opinion(task.id(), row.requestId(), row.sequence(), row.revision(), row.id(),
                "OPINION_GAP", signed.text(), row.actor(), row.at(), current);
    }

    private long currentSequence(String id) {
        List<Long> rows = jdbc.query("SELECT request_seq FROM con2_human_request_state WHERE task_id=?",
                (rs, index) -> rs.getLong(1), id);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private boolean returnsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_HUMAN_RETURN", null)) {
                return tables.next();
            }
        }));
    }

    private boolean returned(String id, long sequence) {
        if (!returnsInstalled()) return false;
        Integer result = jdbc.query("SELECT 1 FROM con2_human_return WHERE task_id=? AND request_seq=?",
                rs -> rs.next() ? 1 : 0, id, sequence);
        return result != null && result != 0;
    }

    private Row row(String id, long sequence) {
        List<Row> rows = jdbc.query("SELECT request_id,request_seq,opinion_id,receipt_id,input_revision,"
                        + "cancel_epoch,actor_ref,org_ref,manifest_id,manifest_sha,text_sha,"
                        + "withdrawal_version,submitted_at FROM con2_human_opinion WHERE task_id=? AND request_seq=?",
                (rs, index) -> new Row(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getLong(12), instant(rs.getTimestamp(13))),
                id, sequence);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static DevHumanRequestStore.Source required(Actor actor, SourceGuard guard,
                                                         boolean save, boolean display, Instant now) {
        DevHumanRequestStore.Source signed = guard.permitted(actor, save, display, now);
        if (signed == null) throw DevTaskCoreFailure.conflict("HUMAN_OPINION_SOURCE_UNAVAILABLE");
        return signed;
    }

    private static DevHumanRequestStore.Source requestRequired(Actor actor,
            DevHumanRequestStore.SourceGuard guard, Instant now) {
        DevHumanRequestStore.Source signed = guard.permitted(actor, false, true, now);
        if (signed == null) throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_SOURCE_UNAVAILABLE");
        return signed;
    }

    private static boolean same(DevHumanRequestStore.Source a, DevHumanRequestStore.Source b) {
        return a.manifestDigest().equals(b.manifestDigest()) && a.textDigest().equals(b.textDigest())
                && a.withdrawalVersion() == b.withdrawalVersion() && a.text().equals(b.text());
    }

    private record Accepted(String id, long revision, long epoch, String actor, long sequence, Instant at) { }
    private record Row(String requestId, long sequence, String id, String receiptId, long revision, long epoch,
                       String actor, String organization, String manifest, String manifestSha, String textSha,
                       long withdrawal, Instant at) { }
}
