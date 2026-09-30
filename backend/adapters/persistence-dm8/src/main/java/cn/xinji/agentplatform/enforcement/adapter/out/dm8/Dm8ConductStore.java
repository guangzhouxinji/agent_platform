package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import cn.xinji.agentplatform.enforcement.conduct.ConductStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

public final class Dm8ConductStore implements ConductStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8ConductStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    @Override public Optional<Rule> rule(String id, long version) {
        var rows = db.query("SELECT payload FROM enf7_conduct_rule WHERE rule_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Rule.class), id, version);
        if (rows.isEmpty()) return Optional.empty();
        var rule = rows.getFirst();
        Integer disabled = db.queryForObject("SELECT COUNT(*) FROM enf7_conduct_disabled "
                + "WHERE rule_id=? AND revision=?", Integer.class, id, version);
        if (disabled != null && disabled == 1)
            return Optional.of(new Rule(rule.id(), rule.version(), rule.organization(), rule.behavior(),
                    "DISABLED", rule.basis(), rule.elements(), rule.defenses(),
                    rule.noDefensesConfirmed(), rule.noDefensesSource(), rule.reviewRef(), rule.author()));
        return Optional.of(rule);
    }

    @Override public Optional<Rule> latestRule(String id) {
        Long latest = db.queryForObject("SELECT MAX(revision) FROM enf7_conduct_rule WHERE rule_id=?",
                Long.class, id);
        return latest == null ? Optional.empty() : rule(id, latest);
    }

    @Override public Optional<Report> report(String id, long version) {
        return db.query("SELECT payload FROM enf7_conduct_report WHERE analysis_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Report.class), id, version).stream().findFirst();
    }

    @Override public long latestReportVersion(String id) {
        Long latest = db.queryForObject("SELECT MAX(revision) FROM enf7_conduct_report WHERE analysis_id=?",
                Long.class, id);
        return latest == null ? 0 : latest;
    }

    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> action) {
        return tx.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf7_conduct_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var prior = db.query("SELECT fingerprint,actor_ref,operation,object_id,object_revision "
                            + "FROM enf7_conduct_receipt WHERE request_key=?",
                    (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5)), key);
            if (!prior.isEmpty()) {
                var saved = prior.getFirst();
                if (!saved.fingerprint().equals(fingerprint) || !saved.actor().equals(actor) ||
                        !saved.operation().equals(operation))
                    throw new IllegalArgumentException("Conduct receipt conflict");
                return new Receipt(key, operation, saved.id(), saved.version());
            }
            Receipt receipt = action.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf7_conduct_receipt(request_key,fingerprint,actor_ref,operation,"
                            + "object_id,object_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    key, fingerprint, actor, operation, receipt.resultId(), receipt.version(), now);
            db.update("INSERT INTO enf7_conduct_event(event_id,request_key,actor_ref,operation,"
                            + "object_id,object_revision,created_at) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), key, actor, operation,
                    receipt.resultId(), receipt.version(), now);
            return receipt;
        });
    }

    @Override public void addRule(Rule rule) {
        db.update("INSERT INTO enf7_conduct_rule(rule_id,revision,organization_id,behavior_type,"
                        + "state,payload,created_at) VALUES(?,?,?,?,?,?,?)",
                rule.id(), rule.version(), rule.organization(), rule.behavior().name(),
                rule.state(), encode(rule), Timestamp.from(clock.instant()));
    }

    @Override public void disable(String id, long version) {
        var active = rule(id, version).orElseThrow(() -> new SecurityException("Rule unavailable"));
        if (!"ACTIVE".equals(active.state())) throw new IllegalArgumentException("Active rule required");
        db.update("INSERT INTO enf7_conduct_disabled(rule_id,revision,disabled_at) VALUES(?,?,?)",
                id, version, Timestamp.from(clock.instant()));
    }

    @Override public void addReport(Report report) {
        db.update("INSERT INTO enf7_conduct_report(analysis_id,revision,organization_id,"
                        + "evidence_organization_id,evidence_organization_revision,rule_id,rule_revision,"
                        + "payload,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                report.id(), report.version(), report.organization(), report.evidenceOrganizationId(),
                report.evidenceOrganizationVersion(), report.ruleId(), report.ruleVersion(),
                encode(report), Timestamp.from(clock.instant()));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("Invalid conduct data", failure); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Conduct data unavailable", failure); }
    }

    private record Stored(String fingerprint, String actor, String operation, String id, long version) { }
}
