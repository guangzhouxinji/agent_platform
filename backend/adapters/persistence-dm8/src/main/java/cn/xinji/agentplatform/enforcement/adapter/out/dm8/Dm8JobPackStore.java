package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.jobpack.JobPackContract.*;

import cn.xinji.agentplatform.enforcement.jobpack.JobPackStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8JobPackStore implements JobPackStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8JobPackStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.db = db; this.tx = tx; this.json = json; this.clock = clock;
    }

    @Override public Optional<Snapshot> latest(String id) {
        Long revision = db.queryForObject("SELECT MAX(revision) FROM enf8_job_pack_revision WHERE package_id=?", Long.class, id);
        return revision == null ? Optional.empty() : snapshot(id, revision);
    }

    @Override public Optional<Snapshot> snapshot(String id, long revision) {
        return db.query("SELECT payload FROM enf8_job_pack_revision WHERE package_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Snapshot.class), id, revision).stream().findFirst();
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> action) {
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf8_job_pack_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var prior = db.query("SELECT fingerprint,actor_ref,operation,package_id,package_revision,state "
                            + "FROM enf8_job_pack_receipt WHERE request_key=?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), State.valueOf(rs.getString(6))), key);
            if (!prior.isEmpty()) {
                var saved = prior.getFirst();
                if (!saved.fingerprint().equals(fingerprint) || !saved.actor().equals(actor) || !saved.operation().equals(operation))
                    throw new IllegalArgumentException("Job package receipt conflict");
                return new Receipt(key, operation, saved.id(), saved.revision(), saved.state());
            }
            Receipt receipt = action.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf8_job_pack_receipt(request_key,fingerprint,actor_ref,operation,package_id,package_revision,state,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.packageId(), receipt.revision(), receipt.state().name(), now);
            db.update("INSERT INTO enf8_job_pack_event(event_id,request_key,actor_ref,operation,package_id,package_revision,state,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, actor, operation, receipt.packageId(), receipt.revision(), receipt.state().name(), now);
            return receipt;
        });
    }

    @Override public void add(Snapshot snapshot) {
        db.update("INSERT INTO enf8_job_pack_revision(package_id,revision,content_revision,organization_id,state,sha256,payload,created_at) VALUES(?,?,?,?,?,?,?,?)",
                snapshot.id(), snapshot.revision(), snapshot.contentRevision(), snapshot.organization(), snapshot.state().name(),
                snapshot.sha256(), encode(snapshot), Timestamp.from(clock.instant()));
        if (snapshot.trigger() != null) db.update("INSERT INTO enf8_integration_trigger(request_id,package_id,package_revision,package_sha256,task_id,state,created_at) VALUES(?,?,?,?,?,'PENDING',?)",
                snapshot.trigger().requestId(), snapshot.id(), snapshot.revision(), snapshot.sha256(), snapshot.trigger().taskId(), Timestamp.from(snapshot.trigger().requestedAt()));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid job package", failure); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Job package unavailable", failure); }
    }
    private record Stored(String fingerprint, String actor, String operation, String id, long revision, State state) { }
}
