package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore.Preview;
import cn.xinji.agentplatform.consultation.taskcore.DevPurposeSelectionStore;
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

public final class Dm8DevPurposeSelectionStore extends Dm8DevTaskCoreSupport
        implements DevPurposeSelectionStore {
    private static final String PURPOSE = "TECHNICAL_SIMILARITY";
    private static final String OPERATION = "SELECT_ANALYSIS_PURPOSE";

    public Dm8DevPurposeSelectionStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public Selection select(Actor human, String taskId, String key, long expectedTaskRevision,
                                      long featureRevision, long focusSequence, String setId, int ordinal,
                                      String analysisPurpose, Guard guard, PreviewCheck preview) {
        validateId(taskId);
        validateKey(key);
        validateId(setId);
        if (expectedTaskRevision < 2 || featureRevision < 1 || focusSequence < 1 || ordinal < 1
                || !PURPOSE.equals(analysisPurpose)) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), OPERATION, taskId);
        String fingerprint = hash(OPERATION, Long.toString(expectedTaskRevision),
                Long.toString(featureRevision), Long.toString(focusSequence), setId,
                Integer.toString(ordinal), analysisPurpose);
        try {
            long sequence = transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt previous = receipt(scope, key);
                if (previous != null) {
                    if (!previous.requestSha().equals(fingerprint)) {
                        throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
                    }
                    Version replay = byReceipt(taskId, previous.id());
                    if (replay == null) throw unavailable();
                    checkedPreview(preview, task, replay.featureSeq(), replay.focusSeq(),
                            replay.setId(), replay.ordinal());
                    return replay.sequence();
                }
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedTaskRevision) {
                    throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                }
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                                + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                                + "AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                Preview input = checkedPreview(preview, task, featureRevision, focusSequence, setId, ordinal);
                Long current = sequence(taskId);
                if (current != null) {
                    Version latest = version(taskId, current);
                    if (latest != null && latest.inputRevision() == task.revision()
                            && latest.featureSeq() == featureRevision && latest.focusSeq() == focusSequence) {
                        throw DevTaskCoreFailure.conflict("PURPOSE_ALREADY_SELECTED");
                    }
                }
                long next = current == null ? 1 : current + 1;
                String receiptId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                                + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,?,?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, OPERATION, task.revision(), utc(now));
                if (current == null) {
                    jdbc.update("INSERT INTO con2_purpose_state(task_id,purpose_seq) VALUES (?,0)", taskId);
                }
                jdbc.update("UPDATE con2_purpose_state SET purpose_seq=? WHERE task_id=?", next, taskId);
                jdbc.update("INSERT INTO con2_purpose_revision(task_id,purpose_seq,input_revision,feature_seq,"
                                + "focus_seq,set_id,ordinal,purpose_code,actor_ref,org_ref,auth_version,"
                                + "receipt_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, next, input.taskRevision(), featureRevision, focusSequence, setId, ordinal,
                        PURPOSE, human.principal(), human.organization(), human.authorizationVersion(),
                        receiptId, utc(now));
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(DevTaskExpiry.sixCalendarMonthsAfter(now)), taskId);
                insertEvent(taskId, nextEvent(taskId), "ANALYSIS_PURPOSE_SELECTED", task.revision(), now, receiptId);
                readable(task(taskId, false), human, guard, databaseNow());
                checkedPreview(preview, task, featureRevision, focusSequence, setId, ordinal);
                return next;
            });
            return read(human, taskId, sequence, guard, preview);
        } catch (DataIntegrityViolationException collision) {
            throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable();
        }
    }

    @Override public Selection current(Actor human, String taskId, Guard guard, PreviewCheck preview) {
        validateId(taskId);
        try {
            return transactions.execute(status -> {
                Task task = task(taskId, true);
                readable(task, human, guard, databaseNow());
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                Long sequence = sequence(taskId);
                Version latest = sequence == null ? null : version(taskId, sequence);
                if (latest == null || !current(task, latest)) {
                    long[] refs = currentRefs(taskId);
                    if (refs != null) preview.read(refs[0], refs[1]);
                    readable(task(taskId, false), human, guard, databaseNow());
                    return new Selection(taskId, task.revision(), 0, "NOT_SELECTED", 0, 0,
                            null, 0, null, null, true);
                }
                checkedPreview(preview, task, latest.featureSeq(), latest.focusSeq(),
                        latest.setId(), latest.ordinal());
                readable(task(taskId, false), human, guard, databaseNow());
                return selection(task, latest, true);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Selection read(Actor human, String taskId, long purposeSequence,
                                    Guard guard, PreviewCheck preview) {
        validateId(taskId);
        if (purposeSequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Task task = task(taskId, true);
                readable(task, human, guard, databaseNow());
                Version version = version(taskId, purposeSequence);
                if (version == null) throw DevTaskCoreFailure.absent();
                boolean current = "ACTIVE".equals(task.state()) && current(task, version);
                if (current) checkedPreview(preview, task, version.featureSeq(), version.focusSeq(),
                        version.setId(), version.ordinal());
                readable(task(taskId, false), human, guard, databaseNow());
                return selection(task, version, current);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Preview checkedPreview(PreviewCheck check, Task task, long feature, long focus,
                                   String setId, int ordinal) {
        Preview input = check.read(feature, focus);
        if (!task.id().equals(input.taskId()) || task.revision() != input.taskRevision()
                || feature != input.featureRevision() || focus != input.focusSequence()
                || !setId.equals(input.setId()) || ordinal != input.ordinal()
                || !"NOT_SELECTED".equals(input.analysisPurpose())
                || !"NOT_ASSESSED".equals(input.technicalRelation())) {
            throw DevTaskCoreFailure.conflict("INPUT_NOT_BOUND");
        }
        return input;
    }

    private boolean current(Task task, Version version) {
        long[] refs = currentRefs(task.id());
        return refs != null && task.revision() == version.inputRevision()
                && refs[0] == version.featureSeq() && refs[1] == version.focusSeq();
    }

    private long[] currentRefs(String taskId) {
        Long feature = jdbc.query("SELECT feature_seq FROM con2_feature_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, taskId);
        Long focus = jdbc.query("SELECT focus_seq FROM con2_result_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, taskId);
        return feature == null || feature < 1 || focus == null || focus < 1
                ? null : new long[] { feature, focus };
    }

    private Long sequence(String taskId) {
        return jdbc.query("SELECT purpose_seq FROM con2_purpose_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, taskId);
    }

    private Version byReceipt(String taskId, String receiptId) {
        List<Version> versions = versions("WHERE task_id=? AND receipt_id=?", taskId, receiptId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private Version version(String taskId, long sequence) {
        List<Version> versions = versions("WHERE task_id=? AND purpose_seq=?", taskId, sequence);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private List<Version> versions(String where, Object... args) {
        return jdbc.query("SELECT purpose_seq,input_revision,feature_seq,focus_seq,set_id,ordinal,"
                        + "purpose_code,actor_ref,receipt_id FROM con2_purpose_revision " + where,
                (rs, i) -> new Version(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8), rs.getString(9)), args);
    }

    private Selection selection(Task task, Version version, boolean current) {
        return new Selection(task.id(), task.revision(), version.sequence(), version.purpose(),
                version.featureSeq(), version.focusSeq(), version.setId(), version.ordinal(),
                version.actor(), version.receiptId(), current);
    }

    private record Version(long sequence, long inputRevision, long featureSeq, long focusSeq,
                           String setId, int ordinal, String purpose, String actor, String receiptId) { }
}
