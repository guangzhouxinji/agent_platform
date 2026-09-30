package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.PublicInput;
import cn.xinji.agentplatform.enforcement.jobpack.RealBusinessCollectionTask;
import cn.xinji.agentplatform.enforcement.jobpack.RealBusinessCollectionTaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8RealBusinessCollectionTaskStore implements RealBusinessCollectionTaskStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8RealBusinessCollectionTaskStore(JdbcTemplate db, TransactionTemplate tx,
                                              ObjectMapper json, Clock clock) {
        this.db = db; this.tx = tx; this.json = json; this.clock = clock;
    }

    @Override public boolean seed(RealBusinessCollectionTask.Seed seed) {
        if (find(seed.taskId()).isPresent()) return false;
        Instant now = clock.instant();
        try {
            return db.update("INSERT INTO enf8_real_business_task(task_id,candidate_id,package_id,title,"
                            + "collection_batch_id,public_payload,assignment_state,workflow_state,version,created_at,updated_at) "
                            + "VALUES(?,?,?,?,?,?,'UNCLAIMED','WAITING_REAL_HUMAN_ACTION',1,?,?)",
                    seed.taskId(), seed.candidateId(), seed.packageId(), seed.title(), seed.collectionBatchId(),
                    encode(seed.publicInput()), Timestamp.from(now), Timestamp.from(now)) == 1;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override public List<RealBusinessCollectionTask> list() {
        return db.query("SELECT t.task_id,t.candidate_id,t.package_id,t.title,t.collection_batch_id,t.public_payload,"
                        + "t.assignment_state,COALESCE((SELECT r.state FROM enf8_job_pack_revision r "
                        + "WHERE r.package_id=t.package_id AND r.revision=(SELECT MAX(x.revision) FROM enf8_job_pack_revision x "
                        + "WHERE x.package_id=t.package_id)),t.workflow_state),t.claimed_by,t.organization_id,t.claimed_at,"
                        + "t.version,t.created_at,t.updated_at FROM enf8_real_business_task t ORDER BY t.created_at,t.task_id",
                (rs, row) -> map(rs));
    }

    @Override public Optional<RealBusinessCollectionTask> find(String taskId) {
        return db.query("SELECT t.task_id,t.candidate_id,t.package_id,t.title,t.collection_batch_id,t.public_payload,"
                        + "t.assignment_state,COALESCE((SELECT r.state FROM enf8_job_pack_revision r "
                        + "WHERE r.package_id=t.package_id AND r.revision=(SELECT MAX(x.revision) FROM enf8_job_pack_revision x "
                        + "WHERE x.package_id=t.package_id)),t.workflow_state),t.claimed_by,t.organization_id,t.claimed_at,"
                        + "t.version,t.created_at,t.updated_at FROM enf8_real_business_task t WHERE t.task_id=?",
                (rs, row) -> map(rs), taskId).stream().findFirst();
    }

    @Override public RealBusinessCollectionTask claim(String taskId, String actor, String organization,
                                                      String requestKey, String fingerprint) {
        return tx.execute(status -> {
            var prior = db.query("SELECT fingerprint,actor_ref,task_id FROM enf8_real_business_task_event "
                            + "WHERE request_key=?", (rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3)),
                    requestKey);
            if (!prior.isEmpty()) {
                var saved = prior.getFirst();
                if (!saved.get(0).equals(fingerprint) || !saved.get(1).equals(actor) || !saved.get(2).equals(taskId))
                    throw new IllegalArgumentException("Collection task receipt conflict");
                return find(taskId).orElseThrow(() -> new IllegalStateException("Collection task unavailable"));
            }
            db.queryForObject("SELECT version FROM enf8_real_business_task WHERE task_id=? FOR UPDATE", Long.class, taskId);
            var current = find(taskId).orElseThrow(() -> new SecurityException("Collection task unavailable"));
            if (current.claimedBy() != null && (!current.claimedBy().equals(actor)
                    || !current.organization().equals(organization)))
                throw new IllegalArgumentException("Collection task already claimed");
            Instant now = clock.instant();
            if (current.claimedBy() == null && db.update("UPDATE enf8_real_business_task SET assignment_state='CLAIMED',"
                            + "claimed_by=?,organization_id=?,claimed_at=?,version=version+1,updated_at=? "
                            + "WHERE task_id=? AND assignment_state='UNCLAIMED' AND version=?",
                    actor, organization, Timestamp.from(now), Timestamp.from(now), taskId, current.version()) != 1)
                throw new IllegalArgumentException("Collection task changed");
            db.update("INSERT INTO enf8_real_business_task_event(request_key,fingerprint,actor_ref,operation,task_id,"
                            + "task_version,occurred_at) VALUES(?,?,?,'CLAIM',?,?,?)",
                    requestKey, fingerprint, actor, taskId, current.claimedBy() == null ? current.version() + 1 : current.version(),
                    Timestamp.from(now));
            return find(taskId).orElseThrow();
        });
    }

    private RealBusinessCollectionTask map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RealBusinessCollectionTask(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), decode(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), instant(rs.getTimestamp(11)), rs.getLong(12), rs.getTimestamp(13).toInstant(),
                rs.getTimestamp(14).toInstant());
    }
    private String encode(PublicInput value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid public input", failure); }
    }
    private PublicInput decode(String value) {
        try { return json.readValue(value, PublicInput.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Public input unavailable", failure); }
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
