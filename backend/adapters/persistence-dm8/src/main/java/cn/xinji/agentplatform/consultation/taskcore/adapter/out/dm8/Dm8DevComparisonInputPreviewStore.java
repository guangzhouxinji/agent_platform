package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevComparisonInputPreviewStore;
import cn.xinji.agentplatform.consultation.taskcore.DevFeatureConfirmationStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTextResultStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevComparisonInputPreviewStore extends Dm8DevTaskCoreSupport
        implements DevComparisonInputPreviewStore {
    private static final String MANIFEST = "DEV-CON-COMPARE-INPUT-01";

    public Dm8DevComparisonInputPreviewStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public Preview read(Actor human, String taskId, long featureRevision, long focusSequence,
                                  Guard guard, Sources sources) {
        validateId(taskId);
        if (featureRevision < 1 || focusSequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                if (!"ACTIVE".equals(task.state())) throw DevTaskCoreFailure.conflict("TASK_CLOSED");
                List<Input> messages = jdbc.query("SELECT revision,body,body_sha FROM con2_message "
                                + "WHERE task_id=? ORDER BY revision DESC",
                        (rs, i) -> new Input(rs.getLong(1), rs.getString(2), rs.getString(3)), taskId);
                if (messages.isEmpty() || messages.get(0).revision() != task.revision()
                        || task.revision() < 2) throw DevTaskCoreFailure.conflict("INPUT_NOT_BOUND");
                Input input = messages.get(0);
                Long latestFeature = jdbc.query("SELECT feature_seq FROM con2_feature_state WHERE task_id=?",
                        rs -> rs.next() ? rs.getLong(1) : null, taskId);
                if (latestFeature == null || latestFeature != featureRevision) {
                    throw DevTaskCoreFailure.conflict("INPUT_NOT_BOUND");
                }
                List<Version> versions = jdbc.query("SELECT input_revision,manifest_id,manifest_sha,input_sha,"
                                + "withdrawal_version FROM con2_feature_revision WHERE task_id=? AND feature_seq=?",
                        (rs, i) -> new Version(rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getLong(5)), taskId, featureRevision);
                if (versions.size() != 1 || versions.get(0).inputRevision() != task.revision()
                        || !MANIFEST.equals(versions.get(0).manifestId())) {
                    throw DevTaskCoreFailure.conflict("INPUT_NOT_BOUND");
                }
                Version version = versions.get(0);
                Long latestFocus = jdbc.query("SELECT focus_seq FROM con2_result_state WHERE task_id=?",
                        rs -> rs.next() ? rs.getLong(1) : null, taskId);
                if (latestFocus == null || latestFocus != focusSequence) {
                    throw DevTaskCoreFailure.conflict("FOCUS_NOT_BOUND");
                }
                List<Focused> focused = jdbc.query("SELECT f.set_id,f.ordinal,f.input_revision,"
                                + "s.input_revision,m.record_id,m.source_version,m.content_sha,m.locator "
                                + "FROM con2_focus f JOIN con2_result_set s ON s.task_id=f.task_id "
                                + "AND s.set_id=f.set_id JOIN con2_result_member m ON m.set_id=f.set_id "
                                + "AND m.ordinal=f.ordinal WHERE f.task_id=? AND f.seq=?",
                        (rs, i) -> new Focused(rs.getString(1), rs.getInt(2), rs.getLong(3),
                                rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                rs.getString(8)), taskId, focusSequence);
                if (focused.size() != 1 || focused.get(0).inputRevision() != task.revision()
                        || focused.get(0).setInputRevision() != task.revision()) {
                    throw DevTaskCoreFailure.conflict("FOCUS_NOT_BOUND");
                }
                Focused focus = focused.get(0);
                List<DevFeatureConfirmationStore.Candidate> stored = jdbc.query("SELECT feature_id,candidate_sha,"
                                + "byte_start,byte_end FROM con2_feature_member WHERE task_id=? AND feature_seq=? "
                                + "ORDER BY feature_id",
                        (rs, i) -> new DevFeatureConfirmationStore.Candidate(rs.getString(1), null,
                                rs.getString(2), rs.getInt(3), rs.getInt(4)), taskId, featureRevision);
                Snapshot snapshot = permitted(human, input, version, focus, stored, guard, sources, now);
                Instant beforeReturn = databaseNow();
                readable(task(taskId, false), human, guard, beforeReturn);
                if (!"ACTIVE".equals(task(taskId, false).state())
                        || !snapshot.equals(permitted(human, input, version, focus, stored, guard, sources,
                                beforeReturn))) throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                return new Preview(taskId, task.revision(), featureRevision, MANIFEST, input.body(),
                        snapshot.features().candidates(), focusSequence, focus.setId(), focus.ordinal(),
                        focus.recordId(), focus.sourceVersion(), focus.digest(), focus.locator(),
                        snapshot.comparisonClaim().claimText(), "NOT_SELECTED", "NOT_ASSESSED",
                        List.of("ANALYSIS_PURPOSE", "PROFESSIONAL_METHOD"));
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Snapshot permitted(Actor human, Input input, Version version, Focused focus,
                               List<DevFeatureConfirmationStore.Candidate> stored, Guard guard,
                               Sources sources, Instant now) {
        if (!hash(input.body()).equals(input.digest())
                || !guard.inputAllowed(human, "SUPPLEMENT", input.body(), now)
                || !guard.inputDisplayAllowed(human, "SUPPLEMENT", input.body(), now)) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        DevFeatureConfirmationStore.Source features = sources.features(human, now);
        if (features == null || !MANIFEST.equals(features.manifestId())
                || !version.manifestSha().equals(features.manifestDigest())
                || !version.inputSha().equals(features.inputDigest())
                || version.withdrawalVersion() != features.withdrawalVersion()
                || !input.digest().equals(features.inputFingerprint())
                || !rawSha(input.body()).equals(features.inputDigest())
                || stored.size() != 3 || features.candidates().size() != 3) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        for (int index = 0; index < 3; index++) {
            var left = stored.get(index);
            var right = features.candidates().get(index);
            if (!left.id().equals(right.id()) || !left.digest().equals(right.digest())
                    || left.byteStart() != right.byteStart() || left.byteEnd() != right.byteEnd()) {
                throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
            }
        }
        DevTextResultStore.Source displayed = sources.claim(human, focus.recordId(), focus.sourceVersion(),
                focus.digest(), now, false);
        DevTextResultStore.Source comparison = sources.claim(human, focus.recordId(), focus.sourceVersion(),
                focus.digest(), now, true);
        if (displayed == null || comparison == null || !"claim:1".equals(focus.locator())
                || !focus.locator().equals(displayed.locator())
                || !focus.locator().equals(comparison.locator())
                || !displayed.claimText().equals(comparison.claimText())) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        return new Snapshot(features, displayed, comparison);
    }

    private static String rawSha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Input(long revision, String body, String digest) { }
    private record Version(long inputRevision, String manifestId, String manifestSha,
                           String inputSha, long withdrawalVersion) { }
    private record Focused(String setId, int ordinal, long inputRevision, long setInputRevision,
                           String recordId, String sourceVersion, String digest, String locator) { }
    private record Snapshot(DevFeatureConfirmationStore.Source features, DevTextResultStore.Source displayedClaim,
                            DevTextResultStore.Source comparisonClaim) { }
}
