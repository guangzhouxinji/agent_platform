package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8EvidenceRuleStore implements EvidenceRuleStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public Dm8EvidenceRuleStore(JdbcTemplate db, TransactionTemplate transaction, Clock clock) {
        this.db = db;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override public Optional<Rule> rule(String id, long revision) {
        var rows = db.query("SELECT organization_id,case_type,basis_ref,state FROM enf4_evidence_rule "
                        + "WHERE rule_id=? AND revision=?",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4)}, id, revision);
        if (rows.isEmpty()) return Optional.empty();
        var values = rows.getFirst();
        var disabled = db.queryForList("SELECT rule_id FROM enf4_evidence_disabled WHERE rule_id=? AND revision=?",
                String.class, id, revision);
        return Optional.of(new Rule(id, revision, values[0], values[1], values[2],
                disabled.isEmpty() ? values[3] : "DISABLED",
                matters(id, revision)));
    }

    @Override public Optional<Rule> latest(String id) {
        var versions = db.queryForList("SELECT MAX(revision) FROM enf4_evidence_rule WHERE rule_id=?",
                Long.class, id);
        return versions.isEmpty() || versions.getFirst() == null
                ? Optional.empty() : rule(id, versions.getFirst());
    }

    private List<Matter> matters(String id, long revision) {
        return db.query("SELECT matter_id,required_flag,expected_material FROM enf4_evidence_matter "
                        + "WHERE rule_id=? AND revision=? ORDER BY matter_id",
                (rs, row) -> new Matter(rs.getString(1), "Y".equals(rs.getString(2)), rs.getString(3)),
                id, revision);
    }

    @Override public Optional<PublicationReview> publication(String id, long revision) {
        return db.query("SELECT basis_version,scope_ref,review_ref,review_digest,reviewed_by "
                        + "FROM enf4_evidence_publication WHERE rule_id=? AND revision=?",
                (rs, row) -> new PublicationReview(id, revision, rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)), id, revision)
                .stream().findFirst();
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> action) {
        return transaction.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf4_evidence_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var previous = db.query("SELECT fingerprint,actor_ref,operation,result_id,revision "
                            + "FROM enf4_evidence_receipt WHERE request_id=?",
                    (rs, row) -> new StoredReceipt(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)), key);
            if (!previous.isEmpty()) {
                var saved = previous.getFirst();
                if (!fingerprint.equals(saved.fingerprint()) || !actor.equals(saved.actor()) ||
                        !operation.equals(saved.operation())) throw new IllegalArgumentException("Receipt conflict");
                return new Receipt(key, operation, saved.resultId(), saved.revision());
            }
            var receipt = action.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf4_evidence_receipt(request_id,fingerprint,actor_ref,operation,"
                            + "result_id,revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.resultId(), receipt.revision(), now);
            db.update("INSERT INTO enf4_evidence_event(event_id,request_id,actor_ref,operation,"
                            + "rule_id,revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, actor, operation,
                    receipt.resultId(), receipt.revision(), now);
            return receipt;
        });
    }

    @Override public void add(Rule rule, String actor) {
        try {
            db.update("INSERT INTO enf4_evidence_rule(rule_id,revision,organization_id,case_type,basis_ref,"
                            + "state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    rule.id(), rule.revision(), rule.organization(), rule.caseType(), rule.basisRef(),
                    rule.state(), actor, Timestamp.from(clock.instant()));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalArgumentException("Evidence rule revision already exists", duplicate);
        }
        for (var matter : rule.matters())
            db.update("INSERT INTO enf4_evidence_matter(rule_id,revision,matter_id,required_flag,expected_material) "
                            + "VALUES(?,?,?,?,?)", rule.id(), rule.revision(), matter.id(),
                    matter.required() ? "Y" : "N", matter.expectedMaterial());
    }

    @Override public void recordReview(String id, long revision, String basisVersion, String scope,
                                       String reviewRef, String digest, String actor) {
        db.update("INSERT INTO enf4_evidence_publication(rule_id,revision,basis_version,scope_ref,"
                        + "review_ref,review_digest,reviewed_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
                id, revision, basisVersion, scope, reviewRef, digest, actor,
                Timestamp.from(clock.instant()));
    }

    @Override public void disable(String id, long revision) {
        var rows = db.queryForList("SELECT state FROM enf4_evidence_rule WHERE rule_id=? AND revision=?",
                String.class, id, revision);
        if (rows.size() != 1 || !"ACTIVE".equals(rows.getFirst()) ||
                !db.queryForList("SELECT rule_id FROM enf4_evidence_disabled WHERE rule_id=? AND revision=?",
                        String.class, id, revision).isEmpty())
            throw new IllegalArgumentException("Active rule required");
        db.update("INSERT INTO enf4_evidence_disabled(rule_id,revision,disabled_at) VALUES(?,?,?)",
                id, revision, Timestamp.from(clock.instant()));
    }

    private record StoredReceipt(String fingerprint, String actor, String operation,
                                 String resultId, long revision) { }
}
