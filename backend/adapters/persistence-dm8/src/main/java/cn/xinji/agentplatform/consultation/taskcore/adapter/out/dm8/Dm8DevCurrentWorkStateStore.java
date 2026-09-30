package cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8;

import cn.xinji.agentplatform.consultation.taskcore.DevCurrentWorkStateStore;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;

public final class Dm8DevCurrentWorkStateStore extends Dm8DevTaskCoreSupport
        implements DevCurrentWorkStateStore {
    public Dm8DevCurrentWorkStateStore(DataSource source, PlatformTransactionManager manager, ZoneId zone) {
        super(source, manager, zone);
    }

    @Override public State read(Actor human, String taskId, Guard guard) {
        validateId(taskId);
        try {
            return transactions.execute(status -> {
                Instant now = databaseNow();
                Task task = task(taskId, true);
                readable(task, human, guard, now);
                List<Sequences> states = jdbc.query("SELECT result_seq,focus_seq FROM con2_result_state WHERE task_id=?",
                        (rs, i) -> new Sequences(rs.getLong(1), rs.getLong(2)), taskId);
                if (states.size() > 1) throw inconsistent();
                long resultSeq = states.isEmpty() ? 0 : states.get(0).result();
                long focusSeq = states.isEmpty() ? 0 : states.get(0).focus();
                Long featureSeq = jdbc.query("SELECT feature_seq FROM con2_feature_state WHERE task_id=?",
                        rs -> rs.next() ? rs.getLong(1) : null, taskId);
                long featureRevision = featureSeq == null ? 0 : featureSeq;
                if (resultSeq < 0 || focusSeq < 0 || featureRevision < 0) throw inconsistent();

                ResultRef result = null;
                if (resultSeq > 0) {
                    List<ResultRef> rows = jdbc.query("SELECT set_id,input_revision FROM con2_result_set "
                                    + "WHERE task_id=? AND seq=?",
                            (rs, i) -> new ResultRef(rs.getString(1), rs.getLong(2),
                                    rs.getLong(2) == task.revision()), taskId, resultSeq);
                    if (rows.size() != 1) throw inconsistent();
                    result = rows.get(0);
                }
                FocusRef focus = null;
                if (focusSeq > 0) {
                    List<FocusRef> rows = jdbc.query("SELECT f.set_id,f.ordinal,f.input_revision "
                                    + "FROM con2_focus f JOIN con2_result_set s ON s.set_id=f.set_id "
                                    + "AND s.task_id=f.task_id WHERE f.task_id=? AND f.seq=?",
                            (rs, i) -> new FocusRef(rs.getString(1), rs.getInt(2), rs.getLong(3),
                                    rs.getLong(3) == task.revision()), taskId, focusSeq);
                    if (rows.size() != 1) throw inconsistent();
                    focus = rows.get(0);
                }
                FeatureRef feature = null;
                if (featureRevision > 0) {
                    List<FeatureRef> rows = jdbc.query("SELECT input_revision,manifest_id FROM con2_feature_revision "
                                    + "WHERE task_id=? AND feature_seq=?",
                            (rs, i) -> new FeatureRef(rs.getLong(1), rs.getString(2),
                                    rs.getLong(1) == task.revision()), taskId, featureRevision);
                    if (rows.size() != 1) throw inconsistent();
                    feature = rows.get(0);
                }
                Instant beforeReturn = databaseNow();
                Task latest = task(taskId, false);
                readable(latest, human, guard, beforeReturn);
                if (latest.revision() != task.revision() || !latest.state().equals(task.state())) {
                    throw inconsistent();
                }
                return new State(taskId, task.revision(), task.state(), resultSeq, focusSeq,
                        featureRevision, result, focus, feature);
            });
        } catch (DataAccessException | TransactionException failure) { throw unavailable(); }
    }

    private static DevTaskCoreFailure inconsistent() {
        return DevTaskCoreFailure.conflict("WORK_STATE_INCONSISTENT");
    }

    private record Sequences(long result, long focus) { }
}
