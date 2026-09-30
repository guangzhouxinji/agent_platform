package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevTextResultStore extends Dm8DevTaskCoreSupport implements DevTextResultStore {
    private static final String METHOD = "SYNTHETIC_FIXED_ORDER";

    public Dm8DevTextResultStore(DataSource dataSource, PlatformTransactionManager manager, ZoneId zone) {
        super(dataSource, manager, zone);
    }

    @Override public ResultSet search(Actor human, String taskId, String key, long expectedRevision,
                                      long expectedSequence, Guard guard, SourceGuard sources) {
        validateId(taskId);
        validateKey(key);
        if (expectedRevision < 1 || expectedSequence < 0) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "SEARCH", taskId);
        String fingerprint = hash("SEARCH", Long.toString(expectedRevision), Long.toString(expectedSequence));
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) return replaySet(prior, fingerprint, human, taskId, guard, sources, now);
                writable(task, expectedRevision, now);
                long sequence = resultSequence(taskId);
                if (sequence != expectedSequence) throw DevTaskCoreFailure.conflict("RESULT_VERSION_CONFLICT");
                String[] order = (sequence % 2 == 0) ? new String[] {"P1", "P2"}
                        : new String[] {"P2", "P1"};
                List<Source> allowed = List.of(required(sources, human, order[0], "V1", null, true, true, false, now),
                        required(sources, human, order[1], "V1", null, true, true, false, now));
                long next = sequence + 1;
                String setId = UUID.randomUUID().toString();
                String receiptId = UUID.randomUUID().toString();
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,operation,"
                        + "committed_revision,committed_at) VALUES (?,?,?,?,?,'SEARCH',?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
                ensureState(taskId);
                jdbc.update("UPDATE con2_result_state SET result_seq=? WHERE task_id=?", next, taskId);
                jdbc.update("INSERT INTO con2_result_set(set_id,task_id,seq,input_revision,method_version,"
                        + "actual_scope,receipt_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
                        setId, taskId, next, task.revision(), "DEV-TEXT-V1", 2, receiptId, utc(now));
                for (int i = 0; i < allowed.size(); i++) {
                    Source source = allowed.get(i);
                    jdbc.update("INSERT INTO con2_result_member(set_id,ordinal,record_id,source_version,"
                            + "content_sha,locator) VALUES (?,?,?,?,?,?)", setId, i + 1, source.recordId(),
                            source.version(), source.digest(), source.locator());
                }
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(expiry), taskId);
                insertEvent(taskId, nextEvent(taskId), "TEXT_RESULT_SET", task.revision(), now, setId);
                Instant commit = databaseNow();
                readable(task(taskId, false), human, guard, commit);
                for (Source source : allowed) required(sources, human, source.recordId(), source.version(),
                        source.digest(), true, true, false, commit);
                return load(taskId, setId, human, guard, sources, commit, false);
            });
        } catch (DataIntegrityViolationException collision) {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                readable(task(taskId, false), human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                return replaySet(prior, fingerprint, human, taskId, guard, sources, now);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public ResultSet read(Actor human, String taskId, String setId, Guard guard, SourceGuard sources) {
        validateId(taskId);
        validateId(setId);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                readable(task(taskId, false), human, guard, now);
                ResultSet result = load(taskId, setId, human, guard, sources, now, true);
                currentHuman(human, guard, databaseNow());
                return result;
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public EvidencePreparation prepareEvidence(Actor human, String taskId, String setId,
                                                           int ordinal, Guard guard, SourceGuard sources) {
        validateId(taskId);
        validateId(setId);
        if (ordinal <= 0) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                List<Long> revisions = jdbc.query("SELECT input_revision FROM con2_result_set "
                        + "WHERE task_id=? AND set_id=?", (rs, i) -> rs.getLong(1), taskId, setId);
                if (revisions.size() != 1) throw DevTaskCoreFailure.absent();
                long inputRevision = revisions.get(0);
                List<Member> members = jdbc.query("SELECT record_id,source_version,content_sha,locator "
                                + "FROM con2_result_member WHERE set_id=? AND ordinal=?",
                        (rs, i) -> new Member(ordinal, rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), false, null), setId, ordinal);
                if (members.size() != 1) throw DevTaskCoreFailure.invalid();
                Member member = members.get(0);
                List<DevTaskCoreStore.Message> messages = jdbc.query("SELECT revision,body,created_at "
                                + "FROM con2_message WHERE task_id=? AND revision<=? ORDER BY revision DESC",
                        (rs, i) -> new DevTaskCoreStore.Message(rs.getLong(1), rs.getLong(1),
                                rs.getString(2), instant(rs.getTimestamp(3))), taskId, inputRevision);
                if (messages.isEmpty()) throw DevTaskCoreFailure.conflict("INPUT_UNAVAILABLE");
                DevTaskCoreStore.Message target = messages.get(0);
                String kind = target.revision() == 1 ? "GOAL" : "SUPPLEMENT";
                if (!guard.inputAllowed(human, kind, target.body(), now)
                        || !guard.inputDisplayAllowed(human, kind, target.body(), now)) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
                Source source = sources.permitted(human, member.recordId(), member.version(),
                        member.digest(), false, false, true, now);
                Instant latest = databaseNow();
                readable(task(taskId, false), human, guard, latest);
                if (!guard.inputAllowed(human, kind, target.body(), latest)
                        || !guard.inputDisplayAllowed(human, kind, target.body(), latest)) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
                source = sources.permitted(human, member.recordId(), member.version(),
                        member.digest(), false, false, true, latest);
                return new EvidencePreparation(taskId, setId, ordinal, inputRevision, target.revision(),
                        target.body(), task.revision() != inputRevision, member.recordId(), member.version(),
                        member.digest(), member.locator(), source == null ? "SOURCE_UNAVAILABLE" : "AVAILABLE",
                        source == null ? null : source.claimText(), "NOT_RECORDED", "NOT_ASSESSED",
                        List.of("CONFIRMED_FEATURES", "PROFESSIONAL_METHOD"));
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Focus focus(Actor human, String taskId, String key, String setId, int ordinal,
                                 long expectedRevision, long expectedSequence, Guard guard, SourceGuard sources) {
        validateId(taskId);
        validateId(setId);
        validateKey(key);
        if (ordinal <= 0 || expectedRevision < 1 || expectedSequence < 0) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "FOCUS", taskId);
        String fingerprint = hash("FOCUS", setId, Integer.toString(ordinal), Long.toString(expectedRevision),
                Long.toString(expectedSequence));
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior != null) return replayFocus(prior, fingerprint, human, taskId, guard, sources, now);
                writable(task, expectedRevision, now);
                long sequence = focusSequence(taskId);
                if (sequence != expectedSequence) throw DevTaskCoreFailure.conflict("FOCUS_VERSION_CONFLICT");
                ResultSet set = load(taskId, setId, human, guard, sources, now, true);
                Member member = set.members().stream().filter(item -> item.ordinal() == ordinal)
                        .findFirst().orElseThrow(DevTaskCoreFailure::invalid);
                if (!member.available()) throw new DevTaskCoreFailure(409, "SOURCE_UNAVAILABLE");
                required(sources, human, member.recordId(), member.version(), member.digest(), false, true, true, now);
                String receiptId = UUID.randomUUID().toString();
                long next = sequence + 1;
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,operation,"
                        + "committed_revision,committed_at) VALUES (?,?,?,?,?,'FOCUS',?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
                jdbc.update("UPDATE con2_result_state SET focus_seq=? WHERE task_id=?", next, taskId);
                jdbc.update("INSERT INTO con2_focus(task_id,seq,set_id,ordinal,input_revision,receipt_id,created_at) "
                        + "VALUES (?,?,?,?,?,?,?)", taskId, next, setId, ordinal, set.inputRevision(), receiptId, utc(now));
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(expiry), taskId);
                insertEvent(taskId, nextEvent(taskId), "TEXT_FOCUS", task.revision(), now, receiptId);
                Instant commit = databaseNow();
                readable(task(taskId, false), human, guard, commit);
                required(sources, human, member.recordId(), member.version(), member.digest(), false, true, true, commit);
                return new Focus(taskId, next, setId, ordinal, member.recordId(), member.version(),
                        member.digest(), set.inputRevision(), expiry);
            });
        } catch (DataIntegrityViolationException collision) {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                readable(task(taskId, false), human, guard, now);
                Receipt prior = receipt(scope, key);
                if (prior == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                return replayFocus(prior, fingerprint, human, taskId, guard, sources, now);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private void writable(Task task, long expectedRevision, Instant now) {
        if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
        if (task.revision() != expectedRevision) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
        Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a ON a.attempt_id=l.attempt_id "
                        + "WHERE l.task_id=? AND a.status='RUNNING' AND l.lease_until>?",
                rs -> rs.next() ? 1 : 0, task.id(), utc(now));
        if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
    }

    private void ensureState(String taskId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM con2_result_state WHERE task_id=?", Long.class, taskId) == 0) {
            jdbc.update("INSERT INTO con2_result_state(task_id,result_seq,focus_seq) VALUES (?,0,0)", taskId);
        }
    }

    private long resultSequence(String taskId) {
        Long value = jdbc.query("SELECT result_seq FROM con2_result_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, taskId);
        return value == null ? 0 : value;
    }

    private long focusSequence(String taskId) {
        Long value = jdbc.query("SELECT focus_seq FROM con2_result_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, taskId);
        return value == null ? 0 : value;
    }

    private ResultSet replaySet(Receipt prior, String fingerprint, Actor human, String taskId, Guard guard,
                                SourceGuard sources, Instant now) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        List<String> ids = jdbc.query("SELECT set_id FROM con2_result_set WHERE receipt_id=? AND task_id=?",
                (rs, i) -> rs.getString(1), prior.id(), taskId);
        if (ids.size() != 1) throw unavailable();
        ResultSet result = load(taskId, ids.get(0), human, guard, sources, now, true);
        if (result.members().stream().anyMatch(item -> !item.available())) {
            throw new DevTaskCoreFailure(409, "SOURCE_UNAVAILABLE");
        }
        return result;
    }

    private Focus replayFocus(Receipt prior, String fingerprint, Actor human, String taskId, Guard guard,
                              SourceGuard sources, Instant now) {
        if (!prior.requestSha().equals(fingerprint)) throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        List<Focus> rows = jdbc.query("SELECT f.seq,f.set_id,f.ordinal,f.input_revision,s.created_at,"
                        + "m.record_id,m.source_version,m.content_sha FROM con2_focus f "
                        + "JOIN con2_result_member m ON m.set_id=f.set_id AND m.ordinal=f.ordinal "
                        + "JOIN con2_result_set s ON s.set_id=f.set_id WHERE f.receipt_id=? AND f.task_id=?",
                (rs, i) -> new Focus(taskId, rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getLong(4),
                        DevTaskExpiry.sixCalendarMonthsAfter(prior.committedAt())), prior.id(), taskId);
        if (rows.size() != 1) throw unavailable();
        Focus focus = rows.get(0);
        required(sources, human, focus.recordId(), focus.sourceVersion(), focus.contentDigest(),
                false, true, true, now);
        return focus;
    }

    private ResultSet load(String taskId, String setId, Actor human, Guard guard, SourceGuard sources,
                           Instant now, boolean includeText) {
        Instant expiresAt = task(taskId, false).expiresAt();
        List<ResultSet> sets = jdbc.query("SELECT seq,input_revision,method_version,actual_scope FROM con2_result_set "
                        + "WHERE task_id=? AND set_id=?", (rs, i) -> new ResultSet(setId, taskId, rs.getLong(1),
                        rs.getLong(2), METHOD, rs.getInt(4), List.of(), expiresAt), taskId, setId);
        if (sets.size() != 1) throw DevTaskCoreFailure.absent();
        List<Member> members = jdbc.query("SELECT ordinal,record_id,source_version,content_sha,locator "
                        + "FROM con2_result_member WHERE set_id=? ORDER BY ordinal", (rs, i) -> {
                    String id = rs.getString(2), version = rs.getString(3), digest = rs.getString(4);
                    Source source = sources.permitted(human, id, version, digest, false, false, true, now);
                    return new Member(rs.getInt(1), id, version, digest, rs.getString(5), source != null,
                            includeText && source != null ? source.claimText() : null);
                }, setId);
        ResultSet set = sets.get(0);
        return new ResultSet(setId, taskId, set.sequence(), set.inputRevision(), METHOD,
                set.actualScope(), members, set.expiresAt());
    }

    private Source required(SourceGuard sources, Actor human, String record, String version, String digest,
                            boolean query, boolean save, boolean display, Instant now) {
        Source source = sources.permitted(human, record, version, digest, query, save, display, now);
        if (source == null) throw new DevTaskCoreFailure(409, "SOURCE_UNAVAILABLE");
        return source;
    }
}
