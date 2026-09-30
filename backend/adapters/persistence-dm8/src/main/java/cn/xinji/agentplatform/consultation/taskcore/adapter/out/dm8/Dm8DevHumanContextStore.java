package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevHumanContextStore;
import cn.xinji.agentplatform.consultation.taskcore.DevHumanRequestStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevHumanContextStore extends Dm8DevTaskCoreSupport implements DevHumanContextStore {
    public Dm8DevHumanContextStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public Context read(Actor human, String taskId, long sequence, Guard guard,
                                  RequestSource requestSource) {
        validateId(taskId);
        if (sequence < 1) throw DevTaskCoreFailure.invalid();
        try {
            return transactions.execute(status -> {
                Task task = task(taskId, true);
                Instant now = databaseNow();
                Package row = authorized(task, human, sequence, guard, now);
                List<StoredMessage> messages = jdbc.query("SELECT seq,revision,body,body_sha FROM con2_message "
                                + "WHERE task_id=? AND revision<=? ORDER BY seq",
                        (rs, index) -> new StoredMessage(rs.getLong(1), rs.getLong(2),
                                rs.getString(3), rs.getString(4)), taskId, row.revision());
                Map<Long, Correction> corrections = correctionsInstalled() ? jdbc.query(
                        "SELECT new_revision,utterance,utterance_sha,new_sha,old_revision,old_sha,manifest_sha FROM con2_correction "
                                + "WHERE task_id=? AND new_revision<=?",
                        (rs, index) -> new Correction(rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                                rs.getString(7)), taskId, row.revision()).stream()
                        .collect(Collectors.toMap(Correction::revision, Function.identity())) : Map.of();
                List<Message> visible = verifyMessages(messages, row.revision(), corrections, human, guard, now);
                DevHumanRequestStore.Source request = verifiedRequest(row, human, requestSource, now);
                Instant beforeReturn = databaseNow();
                Task latest = task(taskId, false);
                Package again = authorized(latest, human, sequence, guard, beforeReturn);
                if (!row.equals(again) || !visible.equals(verifyMessages(messages, row.revision(), corrections,
                        human, guard, beforeReturn)) || !request.equals(verifiedRequest(again, human,
                        requestSource, beforeReturn))) throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
                return new Context(taskId, sequence, row.requestId(), row.revision(), visible, request.text());
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private Package authorized(Task task, Actor human, long sequence, Guard guard, Instant now) {
        currentHuman(human, guard, now);
        if (task == null || !task.organization().equals(human.organization())) throw DevTaskCoreFailure.absent();
        if (!now.isBefore(task.expiresAt()) || "PURGING".equals(task.state())) throw DevTaskCoreFailure.expired();
        List<Package> rows = jdbc.query("SELECT r.request_id,r.input_revision,r.cancel_epoch,"
                        + "r.org_ref,r.manifest_id,r.manifest_sha,r.text_sha,r.withdrawal_version,a.accepted_by "
                        + "FROM con2_human_request r JOIN con2_human_acceptance a ON a.task_id=r.task_id "
                        + "AND a.request_seq=r.request_seq WHERE r.task_id=? AND r.request_seq=?",
                (rs, index) -> new Package(rs.getString(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getLong(8), rs.getString(9)), task.id(), sequence);
        if (rows.size() != 1 || !human.principal().equals(rows.getFirst().acceptedBy())) {
            throw DevTaskCoreFailure.absent();
        }
        Package row = rows.getFirst();
        Long current = jdbc.query("SELECT request_seq FROM con2_human_request_state WHERE task_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, task.id());
        if (!"ACTIVE".equals(task.state()) || task.revision() != row.revision()
                || task.cancelEpoch() != row.epoch() || current == null || current != sequence
                || exists("con2_human_opinion", task.id(), sequence)
                || exists("con2_human_return", task.id(), sequence)) {
            throw DevTaskCoreFailure.conflict("HUMAN_REQUEST_VERSION_CONFLICT");
        }
        return row;
    }

    private boolean exists(String table, String taskId, long sequence) {
        Integer found = jdbc.query("SELECT 1 FROM " + table + " WHERE task_id=? AND request_seq=?",
                rs -> rs.next() ? 1 : 0, taskId, sequence);
        return found != null && found != 0;
    }

    private List<Message> verifyMessages(List<StoredMessage> rows, long revision, Map<Long, Correction> corrections, Actor human,
                                         Guard guard, Instant now) {
        if (rows.size() != revision) throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        if (revision > 2 && (!corrections.containsKey(3L) || corrections.size() != 1)) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        for (int i = 0; i < rows.size(); i++) {
            StoredMessage row = rows.get(i);
            String kind = i == 0 ? "GOAL" : "SUPPLEMENT";
            Correction correction = corrections.get(row.revision());
            if (row.seq() != i + 1 || row.revision() != i + 1 || !hash(row.text()).equals(row.digest())
                    || (correction == null
                        ? !guard.inputAllowed(human, kind, row.text(), now)
                            || !guard.inputDisplayAllowed(human, kind, row.text(), now)
                        : correction.oldRevision() != 2 || correction.revision() != 3
                            || rows.stream().noneMatch(old -> old.revision() == correction.oldRevision()
                                    && plainHash(old.text()).equals(correction.oldSha()))
                            || !plainHash(row.text()).equals(correction.newSha())
                            || !plainHash(correction.utterance()).equals(correction.utteranceSha())
                            || !guard.correctionAllowed(human, "MANIFEST_SHA", correction.manifestSha(), now)
                            || !guard.correctionAllowed(human, "OLD_SHA", correction.oldSha(), now)
                            || !guard.correctionAllowed(human, "NEW_TEXT", row.text(), now)
                            || !guard.correctionAllowed(human, "UTTERANCE", correction.utterance(), now))) {
                throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
            }
        }
        return rows.stream().map(row -> new Message(row.revision(),
                row.seq() == 1 ? "GOAL" : corrections.containsKey(row.revision())
                        ? "CORRECTION" : "SUPPLEMENT", row.text())).toList();
    }

    private boolean correctionsInstalled() {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (var tables = connection.getMetaData().getTables(null, null, "CON2_CORRECTION", null)) {
                return tables.next();
            }
        }));
    }

    private DevHumanRequestStore.Source verifiedRequest(Package row, Actor human,
                                                        RequestSource source, Instant now) {
        DevHumanRequestStore.Source signed = source.permitted(human, now);
        if (signed == null || !row.organization().equals(human.organization())
                || !row.manifestId().equals(signed.manifestId())
                || !row.manifestSha().equals(signed.manifestDigest())
                || !row.textSha().equals(signed.textDigest())
                || row.withdrawal() != signed.withdrawalVersion()) {
            throw DevTaskCoreFailure.conflict("SOURCE_UNAVAILABLE");
        }
        return signed;
    }

    private record Package(String requestId, long revision, long epoch, String organization,
                           String manifestId, String manifestSha, String textSha, long withdrawal,
                           String acceptedBy) { }
    private record StoredMessage(long seq, long revision, String text, String digest) { }
    private record Correction(long revision, String utterance, String utteranceSha, String newSha,
                              long oldRevision, String oldSha, String manifestSha) { }
}
