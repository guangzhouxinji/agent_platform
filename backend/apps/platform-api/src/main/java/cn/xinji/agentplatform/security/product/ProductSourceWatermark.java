package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The independent source epoch prevents an older primary snapshot from reviving a source. */
public final class ProductSourceWatermark {
    public record Current(long epoch, String status, long rowVersion,
                          String contractVersion, String decisionId) { }
    public record Intent(String id, String beforeHistorySha) { }
    private record State(long epoch, String status, String intent, String digest) { }

    private final JdbcTemplate primary;
    private final JdbcTemplate authority;
    private final TransactionTemplate transactions;

    public ProductSourceWatermark(JdbcTemplate primary, JdbcTemplate authority,
            TransactionTemplate transactions) {
        this(primary, authority, transactions, true);
    }

    private ProductSourceWatermark(JdbcTemplate primary, JdbcTemplate authority,
            TransactionTemplate transactions, boolean checkStartup) {
        this.primary = primary;
        this.authority = authority;
        this.transactions = transactions;
        if (checkStartup) assertAllCurrent();
    }

    static ProductSourceWatermark forRecovery(JdbcTemplate primary, JdbcTemplate authority,
            TransactionTemplate transactions) {
        return new ProductSourceWatermark(primary, authority, transactions, false);
    }

    public String recover(String sourceId, String intent, String operator) {
        if (sourceId == null || sourceId.isBlank() || intent == null || intent.isBlank()
                || operator == null || operator.isBlank())
            throw new IllegalArgumentException("Exact source recovery intent and operator required");
        State pending = state(sourceId);
        if (pending == null || !"PENDING".equals(pending.status()) || !intent.equals(pending.intent()))
            throw new IllegalStateException("Source recovery intent is not pending");
        Current main = primaryState(sourceId);
        String actual = digest(sourceId);
        String outcome;
        String target;
        long next;
        if (main == null && pending.epoch() == 0 && pending.digest().equals(actual)) {
            outcome = "S1_RELEASE";
            target = null;
            next = 0;
        } else if (main != null && main.epoch() == pending.epoch()
                && pending.digest().equals(actual) && pending.digest().equals(storedDigest(sourceId))) {
            outcome = "S1_RELEASE";
            target = "ACTIVE".equals(main.status()) ? "READY" : "WITHDRAWN";
            next = pending.epoch();
        } else if (main != null && main.epoch() == pending.epoch() + 1
                && actual.equals(storedDigest(sourceId)) && matchesCommittedIntent(sourceId, main, pending)) {
            outcome = "S2_ADVANCE";
            target = "ACTIVE".equals(main.status()) ? "READY" : "WITHDRAWN";
            next = main.epoch();
        } else {
            throw new IllegalStateException("Source recovery evidence is inconsistent; keep PENDING");
        }
        String result = outcome;
        String destination = target;
        long afterEpoch = next;
        new TransactionTemplate(new DataSourceTransactionManager(authority.getDataSource()))
                .executeWithoutResult(status -> {
                    if (!pending.equals(state(sourceId)) || !Objects.equals(main, primaryState(sourceId))
                            || !actual.equals(digest(sourceId)))
                        throw new IllegalStateException("Source recovery evidence changed before commit");
                    int changed = destination == null
                            ? authority.update("DELETE FROM id4_source_watermark WHERE source_id=? "
                                    + "AND source_epoch=0 AND state='PENDING' AND intent_id=? AND history_sha=?",
                                    sourceId, intent, pending.digest())
                            : authority.update("UPDATE id4_source_watermark SET source_epoch=?,state=?,"
                                    + "intent_id=NULL,history_sha=? WHERE source_id=? AND source_epoch=? "
                                    + "AND state='PENDING' AND intent_id=? AND history_sha=?",
                                    afterEpoch, destination, actual, sourceId, pending.epoch(),
                                    intent, pending.digest());
                    if (changed != 1) throw new IllegalStateException("Source recovery authority changed");
                    authority.update("INSERT INTO id4_source_recovery_event(recovery_id,source_id,intent_id,"
                                    + "operator_ref,outcome,before_epoch,after_epoch,before_history_sha,"
                                    + "after_history_sha,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                            UUID.randomUUID().toString(), sourceId, intent, operator, result,
                            pending.epoch(), afterEpoch, pending.digest(), actual);
                });
        if (destination != null) current(sourceId);
        return outcome;
    }

    private String storedDigest(String sourceId) {
        List<String> values = primary.queryForList("SELECT history_sha FROM con4_source_current "
                + "WHERE source_id=?", String.class, sourceId);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private boolean matchesCommittedIntent(String sourceId, Current main, State pending) {
        List<String> events = primary.query("SELECT operation FROM con4_source_authority_event "
                        + "WHERE source_id=? AND decision_id=? AND before_epoch=? AND after_epoch=? "
                        + "AND after_version=? AND intent_id=? AND before_history_sha=?",
                (rs, row) -> rs.getString(1), sourceId, main.decisionId(), pending.epoch(),
                main.epoch(), main.rowVersion(), pending.intent(), pending.digest());
        return events.size() == 1 && ("ACTIVE".equals(main.status())
                ? "ADMIT".equals(events.getFirst())
                : List.of("WITHDRAW", "EXPIRE").contains(events.getFirst()));
    }

    public void assertAllCurrent() {
        for (String sourceId : primary.queryForList("SELECT source_id FROM con4_source_current", String.class))
            current(sourceId);
        for (String sourceId : authority.queryForList("SELECT source_id FROM id4_source_watermark", String.class))
            current(sourceId);
    }

    public Current current(String sourceId) {
        State state = state(sourceId);
        Current primaryState = primaryState(sourceId);
        if (state == null || primaryState == null || state.intent() != null
                || !state.status().equals("READY") && !state.status().equals("WITHDRAWN")
                || state.epoch() != primaryState.epoch()
                || !state.status().equals("ACTIVE".equals(primaryState.status()) ? "READY" : "WITHDRAWN"))
            throw new IllegalStateException("Source authority is unavailable or unresolved");
        String digest = digest(sourceId);
        List<String> stored = primary.queryForList("SELECT history_sha FROM con4_source_current "
                + "WHERE source_id=?", String.class, sourceId);
        if (stored.size() != 1 || !digest.equals(stored.getFirst()) || !digest.equals(state.digest()))
            throw new IllegalStateException("Source history differs from independent authority");
        return primaryState;
    }

    public void assertUnchanged(String sourceId, long epoch) {
        if (current(sourceId).epoch() != epoch)
            throw new IllegalStateException("Source changed during the protected response");
    }

    public <T> T change(String sourceId, Function<Intent, T> operation) {
        State before = state(sourceId);
        if (before == null) {
            if (primaryState(sourceId) != null) throw new IllegalStateException("Source watermark is missing");
        } else {
            current(sourceId);
        }
        String intent = UUID.randomUUID().toString();
        String original = before == null ? digest(sourceId) : before.digest();
        if (before == null) {
            authority.update("INSERT INTO id4_source_watermark(source_id,source_epoch,state,intent_id,history_sha) "
                    + "VALUES(?,0,'PENDING',?,?)", sourceId, intent, original);
        } else if (authority.update("UPDATE id4_source_watermark SET state='PENDING',intent_id=? "
                + "WHERE source_id=? AND source_epoch=? AND state=? AND intent_id IS NULL AND history_sha=?",
                intent, sourceId, before.epoch(), before.status(), original) != 1) {
            throw new IllegalStateException("Concurrent source authority change");
        }
        long next = (before == null ? 0 : before.epoch()) + 1;
        T result;
        try {
            result = transactions.execute(status -> {
                T value = operation.apply(new Intent(intent, original));
                Current updated = primaryState(sourceId);
                if (updated == null || updated.epoch() != next
                        || !List.of("ACTIVE", "WITHDRAWN").contains(updated.status()))
                    throw new IllegalStateException("Source transition did not advance exactly once");
                if (!matchesCommittedIntent(sourceId, updated,
                        new State(next - 1, "PENDING", intent, original)))
                    throw new IllegalStateException("Source event does not bind the independent intent");
                String actual = digest(sourceId);
                if (primary.update("UPDATE con4_source_current SET history_sha=? "
                        + "WHERE source_id=? AND source_epoch=?", actual, sourceId, next) != 1)
                    throw new IllegalStateException("Source history seal failed");
                return value;
            });
        } catch (RuntimeException failure) {
            if (original.equals(digest(sourceId))) {
                if (before == null) authority.update("DELETE FROM id4_source_watermark WHERE source_id=? "
                        + "AND source_epoch=0 AND state='PENDING' AND intent_id=? AND history_sha=?",
                        sourceId, intent, original);
                else authority.update("UPDATE id4_source_watermark SET state=?,intent_id=NULL "
                        + "WHERE source_id=? AND source_epoch=? AND state='PENDING' AND intent_id=? "
                        + "AND history_sha=?", before.status(), sourceId, before.epoch(), intent, original);
            }
            throw failure;
        }
        Current updated = primaryState(sourceId);
        String actual = digest(sourceId);
        String target = "ACTIVE".equals(updated.status()) ? "READY" : "WITHDRAWN";
        if (authority.update("UPDATE id4_source_watermark SET source_epoch=?,state=?,intent_id=NULL,history_sha=? "
                + "WHERE source_id=? AND source_epoch=? AND state='PENDING' AND intent_id=? AND history_sha=?",
                next, target, actual, sourceId, next - 1, intent, original) != 1)
            throw new IllegalStateException("Independent source commit failed");
        current(sourceId);
        return result;
    }

    private State state(String sourceId) {
        List<State> rows = authority.query("SELECT source_epoch,state,intent_id,history_sha "
                + "FROM id4_source_watermark WHERE source_id=?", (rs, row) -> new State(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), sourceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Current primaryState(String sourceId) {
        List<Current> rows = primary.query("SELECT source_epoch,status,row_version,contract_version,decision_id "
                + "FROM con4_source_current WHERE source_id=?",
                (rs, row) -> new Current(rs.getLong(1), rs.getString(2), rs.getLong(3),
                        rs.getString(4), rs.getString(5)), sourceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String digest(String sourceId) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            primary.query("SELECT source_id,contract_version,decision_id,source_epoch,status,row_version "
                    + "FROM con4_source_current WHERE source_id=?", rs -> {
                        for (int i = 1; i <= 6; i++) field(hash, rs.getString(i));
                    }, sourceId);
            primary.query("SELECT c.contract_version,c.source_version,c.declaration_json,c.request_sha,"
                    + "c.evidence_ref,c.evidence_sha,c.status,c.row_version,a.decision_id,a.rights_ref,"
                    + "a.rights_sha,a.capability_ref,a.capability_sha,a.responsible_party,a.maintainer_ref,"
                    + "a.coverage_sha,a.valid_until,a.decided_at FROM con4_source_current p "
                    + "JOIN con4_source_contract c "
                    + "ON c.source_id=p.source_id AND c.contract_version=p.contract_version "
                    + "JOIN con4_source_admission a ON a.decision_id=p.decision_id WHERE p.source_id=?",
                    rs -> {
                        for (int i = 1; i <= 16; i++) field(hash, rs.getString(i));
                        timestamp(hash, rs.getTimestamp(17));
                        timestamp(hash, rs.getTimestamp(18));
                    }, sourceId);
            primary.query("SELECT f.field_code,f.use_code,f.valid_until FROM con4_source_current p "
                    + "JOIN con4_source_field_use f ON f.decision_id=p.decision_id "
                    + "WHERE p.source_id=? ORDER BY f.field_code,f.use_code", rs -> {
                        for (int i = 1; i <= 2; i++) field(hash, rs.getString(i));
                        timestamp(hash, rs.getTimestamp(3));
                    }, sourceId);
            if (productDecisionTablePresent()) {
                List<String> decisions = primary.query("SELECT d.decision_id,d.decision_version,"
                        + "d.decision_ref,d.decision_sha,d.rights_evidence_status,"
                        + "d.organization_id,d.task_id,d.scope_id,d.retention_ref,"
                        + "d.retention_version,d.retention_evidence_sha "
                        + "FROM con4_source_current p JOIN con4_source_product_decision d "
                        + "ON d.decision_id=p.decision_id WHERE p.source_id=?",
                        (rs, row) -> {
                            for (int i = 1; i <= 11; i++) field(hash, rs.getString(i));
                            return rs.getString(1);
                        }, sourceId);
                if (decisions.size() != 1 && primaryState(sourceId) != null)
                    throw new IllegalStateException("Product-use decision is missing");
            }
            primary.query("SELECT v.verification_id,v.source_version,v.candidate_row_version,"
                    + "v.manifest_ref,v.manifest_sha,v.records_sha,v.inventory_sha,v.record_count,"
                    + "v.raw_count,v.verifier_ref,v.verified_at FROM con4_source_verification v "
                    + "JOIN con4_source_current p ON p.source_id=v.source_id "
                    + "AND p.contract_version=v.contract_version WHERE p.source_id=?",
                    rs -> {
                        for (int i = 1; i <= 10; i++) field(hash, rs.getString(i));
                        timestamp(hash, rs.getTimestamp(11));
                    }, sourceId);
            primary.query("SELECT event_id,contract_version,decision_id,operation,before_epoch,after_epoch,"
                    + "before_version,after_version,intent_id,before_history_sha,actor_ref,request_sha,occurred_at "
                    + "FROM con4_source_authority_event WHERE source_id=? ORDER BY after_epoch,event_id",
                    rs -> {
                        for (int i = 1; i <= 12; i++) field(hash, rs.getString(i));
                        timestamp(hash, rs.getTimestamp(13));
                    }, sourceId);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private boolean productDecisionTablePresent() {
        try {
            return primary.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                try (var tables = connection.getMetaData().getTables(null, connection.getSchema(),
                        "CON4_SOURCE_PRODUCT_DECISION", new String[] {"TABLE"})) {
                    return tables.next();
                }
            });
        } catch (Exception failure) {
            throw new IllegalStateException("Product decision schema is unavailable", failure);
        }
    }
    private static void field(MessageDigest hash, String value) {
        hash.update((value == null ? "-1:" : value.length() + ":" + value)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void timestamp(MessageDigest hash, java.sql.Timestamp value) {
        field(hash, value == null ? null : value.toLocalDateTime().toString());
    }
}
