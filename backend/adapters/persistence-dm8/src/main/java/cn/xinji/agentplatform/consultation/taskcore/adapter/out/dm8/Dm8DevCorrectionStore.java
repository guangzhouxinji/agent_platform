package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevCorrectionStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.CommandResult;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevCorrectionStore extends Dm8DevTaskCoreSupport implements DevCorrectionStore {
    public Dm8DevCorrectionStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public CommandResult correct(Actor human, String taskId, String key, long expectedVersion,
            long sourceRevision, String newText, String utterance, Source source, Guard guard) {
        validateId(taskId);
        validateKey(key);
        if (human == null) throw DevTaskCoreFailure.denied();
        if (source == null || newText == null || utterance == null) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "CORRECT", taskId);
        String fingerprint = hash("CORRECT", Long.toString(expectedVersion), Long.toString(sourceRevision),
                newText, utterance, source.manifestSha());
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                if (!sourceCurrent(human, source, guard, now)) throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                Receipt prior = receipt(scope, key);
                if (prior != null) return replay(prior, fingerprint, human, guard, now);
                if (expectedVersion != 2 || sourceRevision != 2 || !source.newText().equals(newText)
                        || !source.utterance().equals(utterance)) throw DevTaskCoreFailure.invalid();
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedVersion) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                List<String> priorMessage = jdbc.query("SELECT body,body_sha FROM con2_message "
                                + "WHERE task_id=? AND revision=?",
                        (rs, index) -> rs.getString(1).equals(source.oldText())
                                && rs.getString(2).equals(hash(rs.getString(1))) ? rs.getString(1) : "", taskId, 2);
                if (priorMessage.size() != 1 || !source.oldText().equals(priorMessage.get(0))) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                                + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                                + "AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                long revision = 3;
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("UPDATE con2_attempt SET status='STOPPED' WHERE task_id=? AND status<>'STOPPED'", taskId);
                jdbc.update("UPDATE con2_task SET revision=?,last_business_change_at=?,expires_at=? WHERE task_id=?",
                        revision, utc(now), utc(expiry), taskId);
                insertMessage(taskId, revision, human.principal(), newText, now);
                insertAttempt(taskId, revision, task.cancelEpoch(), human, now);
                CommandResult result = insertReceipt(scope, key, fingerprint, taskId, "CORRECT", revision, now, expiry);
                jdbc.update("INSERT INTO con2_correction(task_id,new_revision,old_revision,actor_ref,org_ref,"
                                + "auth_version,manifest_id,manifest_sha,old_sha,new_sha,utterance_sha,utterance,"
                                + "receipt_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, revision, sourceRevision, human.principal(), human.organization(),
                        human.authorizationVersion(), source.manifestId(), source.manifestSha(), source.oldSha(),
                        source.newSha(), source.utteranceSha(), utterance, result.receiptId(), utc(now));
                insertEvent(taskId, nextEvent(taskId), "FACT_CORRECTED", revision, now, Long.toString(revision));
                Instant beforeCommit = databaseNow();
                readable(task(taskId, false), human, guard, beforeCommit);
                if (!sourceCurrent(human, source, guard, beforeCommit)) {
                    throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                }
                return result;
            });
        } catch (DataIntegrityViolationException collision) {
            return resolveCollision(scope, key, fingerprint, human, guard);
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable();
        }
    }

    private boolean sourceCurrent(Actor human, Source source, Guard guard, Instant now) {
        return guard.inputAllowed(human, "SUPPLEMENT", source.oldText(), now)
                && guard.inputDisplayAllowed(human, "SUPPLEMENT", source.oldText(), now)
                && guard.correctionAllowed(human, "NEW_TEXT", source.newText(), now)
                && guard.correctionAllowed(human, "UTTERANCE", source.utterance(), now);
    }
}
