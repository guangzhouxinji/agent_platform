package cn.xinji.agentplatform.security.product;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The independent source epoch prevents an older primary snapshot from reviving a source. */
public final class ProductSourceWatermark {
    private final ProductObservedTables tables;
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
        this.primary = ProductWatermarkJdbc.buffered(primary);
        this.authority = authority;
        this.transactions = transactions;
        this.tables = new ProductObservedTables(this.primary);
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
        if (state == null || state.intent() != null
                || !state.status().equals("READY") && !state.status().equals("WITHDRAWN"))
            throw new IllegalStateException("Source authority is unavailable or unresolved");
        var snapshot = ProductSourceHistoryBatch.read(primary, sourceId, productDecisionTablePresent());
        Current primaryState = snapshot.current();
        State after = state(sourceId);
        if (primaryState == null || !state.equals(after)
                || state.epoch() != primaryState.epoch()
                || !state.status().equals("ACTIVE".equals(primaryState.status()) ? "READY" : "WITHDRAWN"))
            throw new IllegalStateException("Source authority is unavailable or unresolved");
        String digest = snapshot.digest();
        if (!digest.equals(snapshot.storedDigest()) || !digest.equals(state.digest()))
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
        return ProductSourceHistoryBatch.digest(primary, sourceId, productDecisionTablePresent());
    }

    private boolean productDecisionTablePresent() {
        return tables.present("CON4_SOURCE_PRODUCT_DECISION");
    }
}
