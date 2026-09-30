package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskExpiry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevFeatureConfirmationStore extends Dm8DevTaskCoreSupport
        implements DevFeatureConfirmationStore {
    private static final String OLD_MANIFEST = "DEV-CON-FEATURE-CANDIDATES-01";
    private static final String NEW_MANIFEST = "DEV-CON-COMPARE-INPUT-01";
    private static final List<String> IDS = List.of("F1", "F2", "F3");

    public Dm8DevFeatureConfirmationStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public Confirmation confirm(Actor human, String taskId, String key,
                                          long expectedTaskRevision, long expectedFeatureRevision,
                                          String manifestId, List<String> featureIds,
                                          Guard guard, SourceGuard sources) {
        validateId(taskId);
        validateKey(key);
        if (expectedTaskRevision < 2 || expectedFeatureRevision < 0
                || !(OLD_MANIFEST.equals(manifestId) || NEW_MANIFEST.equals(manifestId))
                || !IDS.equals(featureIds)) throw DevTaskCoreFailure.invalid();
        String scope = hash(human.organization(), human.principal(), "CONFIRM_FEATURES", taskId);
        String fingerprint = hash("CONFIRM_FEATURES", Long.toString(expectedTaskRevision),
                Long.toString(expectedFeatureRevision), manifestId, String.join(",", featureIds));
        try {
            Confirmation committed = transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Receipt previous = receipt(scope, key);
                if (previous != null) return replay(previous, fingerprint, human, task, guard, sources, now);
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                if (task.revision() != expectedTaskRevision) throw DevTaskCoreFailure.conflict("VERSION_CONFLICT");
                long sequence = sequence(taskId);
                if (sequence != expectedFeatureRevision) {
                    throw DevTaskCoreFailure.conflict("FEATURE_VERSION_CONFLICT");
                }
                Integer busy = jdbc.query("SELECT 1 FROM con2_lease l JOIN con2_attempt a "
                                + "ON a.attempt_id=l.attempt_id WHERE l.task_id=? "
                                + "AND a.status='RUNNING' AND l.lease_until>?",
                        rs -> rs.next() ? 1 : 0, taskId, utc(now));
                if (busy != null && busy != 0) throw DevTaskCoreFailure.conflict("TASK_BUSY");
                List<Input> messages = jdbc.query("SELECT revision,body,body_sha FROM con2_message "
                                + "WHERE task_id=? ORDER BY revision DESC",
                        (rs, index) -> new Input(rs.getLong(1), rs.getString(2), rs.getString(3)), taskId);
                if (messages.isEmpty() || messages.get(0).revision() != task.revision()) {
                    throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
                }
                Input input = messages.get(0);
                if (!guard.inputAllowed(human, "SUPPLEMENT", input.body(), now)) {
                    throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
                }
                Source source = required(human, sources, manifestId, now, false);
                if (!hash(input.body()).equals(input.digest())
                        || !source.inputFingerprint().equals(input.digest())
                        || !source.inputDigest().equals(rawSha(input.body()))) {
                    throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
                }
                String receiptId = UUID.randomUUID().toString();
                long next = sequence + 1;
                Instant expiry = DevTaskExpiry.sixCalendarMonthsAfter(now);
                jdbc.update("INSERT INTO con2_receipt(receipt_id,scope_sha,idem_sha,request_sha,task_id,"
                                + "operation,committed_revision,committed_at) VALUES (?,?,?,?,?,'CONFIRM_FEATURES',?,?)",
                        receiptId, scope, hash(key), fingerprint, taskId, task.revision(), utc(now));
                if (sequence == 0) jdbc.update("INSERT INTO con2_feature_state(task_id,feature_seq) VALUES (?,0)", taskId);
                jdbc.update("UPDATE con2_feature_state SET feature_seq=? WHERE task_id=?", next, taskId);
                jdbc.update("INSERT INTO con2_feature_revision(task_id,feature_seq,input_revision,actor_ref,"
                                + "org_ref,auth_version,manifest_id,manifest_sha,input_sha,withdrawal_version,"
                                + "receipt_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        taskId, next, input.revision(), human.principal(), human.organization(),
                        human.authorizationVersion(), source.manifestId(), source.manifestDigest(),
                        source.inputDigest(), source.withdrawalVersion(), receiptId, utc(now));
                for (Candidate item : source.candidates()) {
                    jdbc.update("INSERT INTO con2_feature_member(task_id,feature_seq,feature_id,candidate_sha,"
                                    + "byte_start,byte_end) VALUES (?,?,?,?,?,?)", taskId, next, item.id(),
                            item.digest(), item.byteStart(), item.byteEnd());
                }
                jdbc.update("UPDATE con2_task SET last_business_change_at=?,expires_at=? WHERE task_id=?",
                        utc(now), utc(expiry), taskId);
                insertEvent(taskId, nextEvent(taskId), "FEATURE_CONFIRMED", task.revision(), now, receiptId);
                Instant beforeCommit = databaseNow();
                readable(task(taskId, false), human, guard, beforeCommit);
                if (!guard.inputAllowed(human, "SUPPLEMENT", input.body(), beforeCommit)
                        || !matches(source, required(human, sources, manifestId, beforeCommit, false))) {
                    throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
                }
                return load(task, next, human, guard, sources, beforeCommit);
            });
            return read(human, taskId, committed.featureRevision(), guard, sources);
        } catch (DataIntegrityViolationException collision) {
            try {
                return transactions.execute(status -> {
                    Instant now = databaseNow();
                    Task task = task(taskId, true);
                    readable(task, human, guard, now);
                    Receipt previous = receipt(scope, key);
                    if (previous == null) throw DevTaskCoreFailure.conflict("WRITE_CONFLICT");
                    return replay(previous, fingerprint, human, task, guard, sources, now);
                });
            } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    @Override public Confirmation read(Actor human, String taskId, long featureRevision,
                                       Guard guard, SourceGuard sources) {
        validateId(taskId);
        if (featureRevision < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                Confirmation result = load(task, featureRevision, human, guard, sources, now);
                readable(task(taskId, false), human, guard, databaseNow());
                return result;
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Confirmation replay(Receipt previous, String fingerprint, Actor human, Task task,
                                Guard guard, SourceGuard sources, Instant now) {
        if (!previous.requestSha().equals(fingerprint)) {
            throw DevTaskCoreFailure.conflict("IDEMPOTENCY_CONFLICT");
        }
        if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
        List<Long> revisions = jdbc.query("SELECT feature_seq FROM con2_feature_revision "
                        + "WHERE task_id=? AND receipt_id=?", (rs, index) -> rs.getLong(1), task.id(), previous.id());
        if (revisions.size() != 1) throw unavailable();
        return load(task, revisions.get(0), human, guard, sources, now);
    }

    private Confirmation load(Task task, long featureRevision, Actor human, Guard guard,
                              SourceGuard sources, Instant now) {
        List<Version> versions = jdbc.query("SELECT input_revision,actor_ref,receipt_id,manifest_id,"
                        + "manifest_sha,input_sha,withdrawal_version FROM con2_feature_revision "
                        + "WHERE task_id=? AND feature_seq=?",
                (rs, index) -> new Version(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)),
                task.id(), featureRevision);
        if (versions.size() != 1) throw DevTaskCoreFailure.absent();
        Version version = versions.get(0);
        Source source = required(human, sources, version.manifestId(), now, true);
        if (!source.manifestId().equals(version.manifestId())
                || !source.manifestDigest().equals(version.manifestDigest())
                || !source.inputDigest().equals(version.inputDigest())
                || source.withdrawalVersion() != version.withdrawalVersion()) {
            throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
        }
        List<String> inputDigests = jdbc.query("SELECT body_sha FROM con2_message "
                        + "WHERE task_id=? AND revision=?", (rs, index) -> rs.getString(1),
                task.id(), version.inputRevision());
        if (inputDigests.size() != 1 || !source.inputFingerprint().equals(inputDigests.get(0))) {
            throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
        }
        List<Candidate> members = jdbc.query("SELECT feature_id,candidate_sha,byte_start,byte_end "
                        + "FROM con2_feature_member WHERE task_id=? AND feature_seq=? ORDER BY feature_id",
                (rs, index) -> new Candidate(rs.getString(1), null, rs.getString(2),
                        rs.getInt(3), rs.getInt(4)), task.id(), featureRevision);
        if (members.size() != 3) throw unavailable();
        for (int index = 0; index < 3; index++) {
            Candidate actual = members.get(index), signed = source.candidates().get(index);
            if (!actual.id().equals(signed.id()) || !actual.digest().equals(signed.digest())
                    || actual.byteStart() != signed.byteStart() || actual.byteEnd() != signed.byteEnd()) {
                throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
            }
        }
        currentHuman(human, guard, databaseNow());
        if (!matches(source, required(human, sources, version.manifestId(), databaseNow(), true))) {
            throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
        }
        return new Confirmation(task.id(), featureRevision, version.inputRevision(), version.actorRef(),
                version.receiptId(), task.revision() != version.inputRevision(), source.candidates());
    }

    private static boolean matches(Source left, Source right) {
        return left.manifestDigest().equals(right.manifestDigest())
                && left.inputDigest().equals(right.inputDigest())
                && left.inputFingerprint().equals(right.inputFingerprint())
                && left.withdrawalVersion() == right.withdrawalVersion()
                && left.candidates().equals(right.candidates());
    }

    private static Source required(Actor human, SourceGuard sources, String manifestId,
                                   Instant now, boolean display) {
        Source source = sources.permitted(human, manifestId, display, now);
        if (source == null || !manifestId.equals(source.manifestId())
                || source.candidates().size() != 3) {
            throw DevTaskCoreFailure.conflict("FEATURE_SOURCE_UNAVAILABLE");
        }
        return source;
    }

    private long sequence(String taskId) {
        Long value = jdbc.query("SELECT feature_seq FROM con2_feature_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : 0L, taskId);
        return value == null ? 0 : value;
    }

    private static String rawSha(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Input(long revision, String body, String digest) { }
    private record Version(long inputRevision, String actorRef, String receiptId, String manifestId,
                           String manifestDigest, String inputDigest, long withdrawalVersion) { }
}
