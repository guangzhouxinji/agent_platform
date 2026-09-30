package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.handoff.HandoffStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8HandoffStore implements HandoffStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8HandoffStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    @Override public Optional<Package> findPackage(String id, long version) {
        return db.query("SELECT payload FROM enf5_work_package_revision WHERE package_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Package.class), id, version).stream().findFirst();
    }

    @Override public long latestPackage(String id) {
        Long latest = db.queryForObject("SELECT MAX(revision) FROM enf5_work_package_revision "
                + "WHERE package_id=?", Long.class, id);
        return latest == null ? 0 : latest;
    }

    @Override public Optional<Request> findRequest(String id) {
        return db.query("SELECT payload FROM enf5_human_request WHERE request_id=?",
                (rs, row) -> decode(rs.getString(1), Request.class), id).stream().findFirst();
    }

    @Override public List<Request> findRequests() {
        return db.query("SELECT payload FROM enf5_human_request ORDER BY created_at,request_id",
                (rs, row) -> decode(rs.getString(1), Request.class));
    }

    @Override public Optional<Result> findResult(String id) {
        return db.query("SELECT payload FROM enf5_human_result_revision WHERE request_id=? ORDER BY revision DESC",
                (rs, row) -> decode(rs.getString(1), Result.class), id).stream().findFirst();
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> body) {
        return command(key, fingerprint, actor, operation, null, body);
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     String reason, Supplier<Receipt> body) {
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf5_handoff_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var prior = db.query("SELECT fingerprint,actor_ref,operation,object_id,object_revision "
                            + "FROM enf5_handoff_receipt WHERE request_key=?",
                    (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)}, key);
            if (!prior.isEmpty()) {
                var saved = prior.getFirst();
                if (!fingerprint.equals(saved[0]) || !actor.equals(saved[1]) || !operation.equals(saved[2]))
                    throw new IllegalArgumentException("Receipt conflict");
                return new Receipt(key, (String) saved[3], (long) saved[4], operation);
            }
            var receipt = body.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf5_handoff_receipt(request_key,fingerprint,actor_ref,operation,"
                            + "object_id,object_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.objectId(), receipt.version(), now);
            db.update("INSERT INTO enf5_human_event(event_id,request_key,object_id,object_revision,"
                            + "actor_ref,operation,reason,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, receipt.objectId(), receipt.version(),
                    actor, operation, reason, now);
            return receipt;
        });
    }

    @Override public void addPackage(Package value) {
        db.update("INSERT INTO enf5_work_package_revision(package_id,revision,owner_organization,"
                        + "evidence_organization_id,evidence_organization_revision,payload,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", value.id(), value.version(), value.organization(),
                value.evidenceOrganizationId(), value.evidenceOrganizationVersion(), encode(value),
                Timestamp.from(clock.instant()));
    }

    @Override public void addRequest(Request value) {
        db.update("INSERT INTO enf5_human_request(request_id,package_id,package_revision,"
                        + "request_revision,request_state,payload,created_at) VALUES(?,?,?,?,?,?,?)",
                value.id(), value.packageId(), value.packageVersion(), value.version(), value.state(),
                encode(value), Timestamp.from(clock.instant()));
    }

    @Override public void updateRequest(Request previous, Request next) {
        int changed = db.update("UPDATE enf5_human_request SET request_revision=?,request_state=?,payload=? "
                        + "WHERE request_id=? AND request_revision=? AND request_state=?",
                next.version(), next.state(), encode(next), next.id(), previous.version(), previous.state());
        if (changed != 1) throw new IllegalArgumentException("Request version conflict");
    }

    @Override public void addResult(Result value) {
        db.update("INSERT INTO enf5_human_result_revision(request_id,revision,payload,created_at) "
                        + "VALUES(?,?,?,?)", value.requestId(), value.version(), encode(value),
                Timestamp.from(clock.instant()));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid handoff payload", invalid); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Handoff payload unreadable", invalid); }
    }
}
