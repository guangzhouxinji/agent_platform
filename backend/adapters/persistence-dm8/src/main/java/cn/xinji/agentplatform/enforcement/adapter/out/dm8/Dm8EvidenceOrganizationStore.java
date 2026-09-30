package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8EvidenceOrganizationStore implements EvidenceOrganizationStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8EvidenceOrganizationStore(JdbcTemplate db, TransactionTemplate tx,
                                        ObjectMapper json, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    @Override public Optional<Revision> revision(String id, long version) {
        return db.query("SELECT payload FROM enf4_organization WHERE organization_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1)), id, version).stream().findFirst();
    }

    @Override public long latest(String id) {
        Long value = db.queryForObject("SELECT MAX(revision) FROM enf4_organization WHERE organization_id=?",
                Long.class, id);
        return value == null ? 0 : value;
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> body) {
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf4_evidence_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var previous = db.query("SELECT fingerprint,actor_ref,operation,organization_id,revision "
                            + "FROM enf4_organization_receipt WHERE request_id=?",
                    (rs, row) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)}, key);
            if (!previous.isEmpty()) {
                var saved = previous.getFirst();
                if (!fingerprint.equals(saved[0]) || !actor.equals(saved[1]) || !operation.equals(saved[2]))
                    throw new IllegalArgumentException("Receipt conflict");
                return new Receipt(key, (String) saved[3], (long) saved[4]);
            }
            Receipt receipt = body.get();
            Timestamp now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf4_organization_receipt(request_id,fingerprint,actor_ref,operation,"
                            + "organization_id,revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.id(), receipt.revision(), now);
            db.update("INSERT INTO enf4_organization_event(event_id,request_id,organization_id,revision,"
                            + "actor_ref,operation,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, receipt.id(), receipt.revision(), actor, operation, now);
            return receipt;
        });
    }

    @Override public void append(Revision revision, String actor) {
        try {
            db.update("INSERT INTO enf4_organization(organization_id,revision,owner_organization,case_ref,"
                            + "case_version,rule_id,rule_revision,review_state,payload,created_by,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    revision.id(), revision.version(), revision.organization(), revision.caseRef(),
                    revision.caseVersion(), revision.ruleId(), revision.ruleVersion(), revision.state(),
                    json.writeValueAsString(revision), actor, Timestamp.from(clock.instant()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException("Invalid organization payload", impossible);
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalArgumentException("Organization revision already exists", duplicate);
        }
    }

    private Revision decode(String value) {
        try { return json.readValue(value, Revision.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Organization payload unreadable", invalid); }
    }
}
