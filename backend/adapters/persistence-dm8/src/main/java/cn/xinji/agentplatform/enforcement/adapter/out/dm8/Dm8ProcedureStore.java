package cn.xinji.agentplatform.enforcement.adapter.out.dm8;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import cn.xinji.agentplatform.enforcement.procedure.ProcedureStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class Dm8ProcedureStore implements ProcedureStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Clock clock;

    public Dm8ProcedureStore(JdbcTemplate db, TransactionTemplate transaction,
                             ObjectMapper json, Clock clock) {
        this.db = db;
        this.transaction = transaction;
        this.json = json;
        this.clock = clock;
    }

    @Override public Optional<Rule> rule(String id, long version) {
        var rows = db.query("SELECT payload FROM enf6_procedure_rule WHERE rule_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Rule.class), id, version);
        if (rows.isEmpty()) return Optional.empty();
        var value = rows.getFirst();
        boolean disabled = !db.queryForList("SELECT rule_id FROM enf6_procedure_disabled WHERE rule_id=? AND revision=?",
                String.class, id, version).isEmpty();
        return Optional.of(disabled ? new Rule(value.id(), value.version(), value.organization(), value.region(),
                value.procedure(), value.stage(), "DISABLED", value.basis(), value.clock(),
                value.requiredEvents(), value.requiredMaterials(), value.author(), value.reviewer(), value.reviewRef()) : value);
    }
    @Override public Optional<Rule> latestRule(String id) {
        return latest("enf6_procedure_rule", "rule_id", id).flatMap(v -> rule(id, v));
    }
    @Override public java.util.List<Rule> activeRules(String organization, String region,
                                                       String procedure, String stage) {
        var candidates = db.query("SELECT rule_id,revision FROM enf6_procedure_rule "
                        + "WHERE organization_id=? AND state='ACTIVE'",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, organization);
        return candidates.stream().map(row -> rule(row[0], Long.parseLong(row[1])).orElseThrow())
                .filter(rule -> "ACTIVE".equals(rule.state()) && region.equals(rule.region()) &&
                        procedure.equals(rule.procedure()) && stage.equals(rule.stage())).toList();
    }
    @Override public Optional<Report> report(String id, long version) {
        return db.query("SELECT payload FROM enf6_procedure_report WHERE report_id=? AND revision=?",
                (rs, row) -> decode(rs.getString(1), Report.class), id, version).stream().findFirst();
    }
    @Override public Optional<Report> latestReport(String id) {
        return latest("enf6_procedure_report", "report_id", id).flatMap(v -> report(id, v));
    }
    private Optional<Long> latest(String table, String idColumn, String id) {
        var rows = db.queryForList("SELECT MAX(revision) FROM " + table + " WHERE " + idColumn + "=?",
                Long.class, id);
        return rows.isEmpty() || rows.getFirst() == null ? Optional.empty() : Optional.of(rows.getFirst());
    }
    @Override public Receipt command(String key, String fingerprint, String actor, String operation,
                                     Supplier<Receipt> action) {
        return transaction.execute(status -> {
            db.queryForObject("SELECT lock_id FROM enf6_procedure_receipt_lock WHERE lock_id=? FOR UPDATE",
                    Integer.class, Math.floorMod(key.hashCode(), 8));
            var prior = db.query("SELECT fingerprint,actor_ref,operation,result_id,revision "
                            + "FROM enf6_procedure_receipt WHERE request_id=?",
                    (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5)}, key);
            if (!prior.isEmpty()) {
                var saved = prior.getFirst();
                if (!fingerprint.equals(saved[0]) || !actor.equals(saved[1]) || !operation.equals(saved[2]))
                    throw new IllegalArgumentException("Receipt conflict");
                return new Receipt(key, operation, saved[3], Long.parseLong(saved[4]));
            }
            var receipt = action.get();
            var now = Timestamp.from(clock.instant());
            db.update("INSERT INTO enf6_procedure_receipt(request_id,fingerprint,actor_ref,operation,result_id,revision,created_at) "
                    + "VALUES(?,?,?,?,?,?,?)", key, fingerprint, actor, operation, receipt.resultId(), receipt.version(), now);
            db.update("INSERT INTO enf6_procedure_event(event_id,request_id,actor_ref,operation,result_id,revision,created_at) "
                    + "VALUES(?,?,?,?,?,?,?)", UUID.randomUUID().toString(), key, actor, operation,
                    receipt.resultId(), receipt.version(), now);
            return receipt;
        });
    }
    @Override public void addRule(Rule rule) {
        db.update("INSERT INTO enf6_procedure_rule(rule_id,revision,organization_id,state,payload,created_at) "
                + "VALUES(?,?,?,?,?,?)", rule.id(), rule.version(), rule.organization(), rule.state(),
                encode(rule), Timestamp.from(clock.instant()));
    }
    @Override public void disableRule(String id, long version) {
        var rule = rule(id, version).orElseThrow(() -> new IllegalArgumentException("Rule unavailable"));
        if (!"ACTIVE".equals(rule.state())) throw new IllegalArgumentException("Active rule required");
        db.update("INSERT INTO enf6_procedure_disabled(rule_id,revision,disabled_at) VALUES(?,?,?)",
                id, version, Timestamp.from(clock.instant()));
    }
    @Override public void addReport(Report report) {
        db.update("INSERT INTO enf6_procedure_report(report_id,revision,organization_id,case_ref,case_version,payload,created_at) "
                + "VALUES(?,?,?,?,?,?,?)", report.id(), report.version(), report.organization(),
                report.caseRef(), report.caseVersion(), encode(report), Timestamp.from(clock.instant()));
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Procedure serialization failed", invalid); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception invalid) { throw new IllegalStateException("Procedure record invalid", invalid); }
    }
}
