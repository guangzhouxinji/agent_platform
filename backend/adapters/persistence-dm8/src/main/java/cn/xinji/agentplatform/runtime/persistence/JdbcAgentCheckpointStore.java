package cn.xinji.agentplatform.runtime.persistence;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Business-data-service adapter. Authorization/fencing are live callbacks, never checkpoint snapshots. */
public final class JdbcAgentCheckpointStore implements CheckpointStore {
    public record Binding(String agentId, String taskId, String goalId, String goalVersion,
                          String scope, Instant expiresAt) {
        public Binding {
            for (String value : List.of(agentId, taskId, goalId, goalVersion, scope))
                if (value.isBlank() || value.length() > 240) throw new IllegalArgumentException("INVALID_RUN_BINDING");
            Objects.requireNonNull(expiresAt);
            expiresAt = expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        }
        void require(Checkpoint c) {
            if (!agentId.equals(c.definition().agentId()) || !taskId.equals(c.task().id())
                    || !scope.equals(c.task().scope()) || !goalId.equals(c.task().goal().id())
                    || !goalVersion.equals(c.task().goal().version())) throw new SecurityException("RUN_SCOPE_MISMATCH");
        }
    }
    public interface CurrentExecution {
        /** Recheck parent Task/Goal/Input/source rights/current identity and the domain execution fence. */
        void require(Binding binding, Checkpoint checkpoint);
    }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AgentCheckpointCodec codec;
    private final Binding binding;
    private final CurrentExecution current;
    private final Clock clock;
    public JdbcAgentCheckpointStore(JdbcTemplate db, TransactionTemplate tx, AgentCheckpointCodec codec,
            Binding binding, CurrentExecution current, Clock clock) {
        this.db = Objects.requireNonNull(db); this.tx = Objects.requireNonNull(tx); this.codec = Objects.requireNonNull(codec);
        this.binding = Objects.requireNonNull(binding); this.current = Objects.requireNonNull(current); this.clock = Objects.requireNonNull(clock);
    }
    @Override public Checkpoint load(String runId) {
        live(); current.require(binding, null);
        var rows = db.query("SELECT h.head_version,r.ciphertext,r.nonce,r.payload_sha,h.expires_at,h.goal_id,h.goal_version,h.scope_ref "
                + "FROM agent_run_head h LEFT JOIN agent_run_revision r ON r.run_id=h.run_id AND r.revision=h.head_version "
                + "WHERE h.run_id=? AND h.agent_id=? AND h.task_id=?", (rs,n) -> {
                    if (rs.getString(2) == null) throw new IllegalStateException("RUN_HEAD_CORRUPT");
                    if (!binding.expiresAt().equals(rs.getTimestamp(5).toInstant()) || !binding.goalId().equals(rs.getString(6))
                            || !binding.goalVersion().equals(rs.getString(7)) || !binding.scope().equals(rs.getString(8)))
                        throw new SecurityException("RUN_SCOPE_MISMATCH");
                    return codec.decode(runId, binding.agentId(), binding.taskId(), rs.getLong(1),
                            new AgentCheckpointCodec.Encoded(rs.getString(2), rs.getString(3), rs.getString(4))).checkpoint();
                }, runId, binding.agentId(), binding.taskId());
        if (rows.size() > 1) throw new IllegalStateException("RUN_HEAD_CORRUPT");
        Checkpoint c = rows.isEmpty() ? null : rows.getFirst();
        if (c != null) binding.require(c);
        current.require(binding, c); live(); return c;
    }
    @Override public void commit(long expectedVersion, Checkpoint next, AgentEvent event) {
        binding.require(next); live();
        if (expectedVersion < -1 || next.version() != expectedVersion + 1)
            throw new IllegalArgumentException("CHECKPOINT_VERSION_MISMATCH");
        if (expectedVersion==-1 && (next.phase()!=Phase.OBSERVE || next.plan()!=null || next.invocations()!=0
                || next.replans()!=0 || !next.observations().isEmpty() || next.pending()!=null))
            throw new IllegalArgumentException("RUN_INITIAL_CHECKPOINT_REQUIRED");
        var encoded = codec.encode(next, event);
        tx.executeWithoutResult(status -> {
            current.require(binding, next); live(); Timestamp now = Timestamp.from(clock.instant());
            if (expectedVersion == -1) {
                db.update("INSERT INTO agent_run_head(run_id,agent_id,task_id,goal_id,goal_version,scope_ref,head_version,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,?,0,?,?,?)",
                        next.runId(), binding.agentId(), binding.taskId(), binding.goalId(), binding.goalVersion(),
                        binding.scope(), Timestamp.from(binding.expiresAt()), now, now);
            } else {
                int changed = db.update("UPDATE agent_run_head SET head_version=?,updated_at=? WHERE run_id=? AND agent_id=? AND task_id=? "
                        + "AND goal_id=? AND goal_version=? AND scope_ref=? AND head_version=? AND expires_at>?",
                        next.version(), now, next.runId(), binding.agentId(), binding.taskId(), binding.goalId(),
                        binding.goalVersion(), binding.scope(), expectedVersion, now);
                if (changed != 1) throw new IllegalStateException("STALE_CHECKPOINT");
                var prior = db.query("SELECT ciphertext,nonce,payload_sha FROM agent_run_revision WHERE run_id=? AND revision=?",
                        (rs,n) -> codec.decode(next.runId(), binding.agentId(), binding.taskId(), expectedVersion,
                                new AgentCheckpointCodec.Encoded(rs.getString(1),rs.getString(2),rs.getString(3))).checkpoint(), next.runId(), expectedVersion);
                if (prior.size()!=1 || !prior.getFirst().definition().equals(next.definition())
                        || !prior.getFirst().task().equals(next.task()) || !prior.getFirst().budget().equals(next.budget())
                        || !prior.getFirst().startedAt().equals(next.startedAt()) || prior.getFirst().phase()==Phase.STOP)
                    throw new IllegalStateException("RUN_BINDING_CHANGED");
            }
            db.update("INSERT INTO agent_run_revision(run_id,revision,ciphertext,nonce,payload_sha,created_at) VALUES(?,?,?,?,?,?)",
                    next.runId(), next.version(), encoded.ciphertext(), encoded.nonce(), encoded.sha256(), now);
            current.require(binding, next); live();
        });
    }
    public List<AgentEvent> events(String runId) {
        if (load(runId) == null) return List.of();
        var events = db.query("SELECT revision,ciphertext,nonce,payload_sha FROM agent_run_revision WHERE run_id=? ORDER BY revision",
                (rs,n) -> codec.decode(runId,binding.agentId(),binding.taskId(),rs.getLong(1),
                        new AgentCheckpointCodec.Encoded(rs.getString(2),rs.getString(3),rs.getString(4))).event(), runId);
        current.require(binding, null); live(); return List.copyOf(events);
    }
    /** Executes only within this already authorized parent scope and its recorded expiry. */
    public int purgeExpired() {
        return tx.execute(status -> {
            int removed = db.update("DELETE FROM agent_run_revision WHERE run_id IN (SELECT run_id FROM agent_run_head "
                    + "WHERE agent_id=? AND task_id=? AND expires_at<=?)",binding.agentId(),binding.taskId(),Timestamp.from(clock.instant()));
            db.update("DELETE FROM agent_run_head WHERE agent_id=? AND task_id=? AND expires_at<=?",binding.agentId(),binding.taskId(),Timestamp.from(clock.instant()));
            return removed;
        });
    }
    private void live() { if (!binding.expiresAt().isAfter(clock.instant())) throw new SecurityException("RUN_RETENTION_EXPIRED"); }
}
