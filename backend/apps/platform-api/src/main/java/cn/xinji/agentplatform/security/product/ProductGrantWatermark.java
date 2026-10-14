package cn.xinji.agentplatform.security.product;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent authority: an unfinished cross-store write closes every protected request. */
public final class ProductGrantWatermark {
    private record State(long generation, String state, String intent, String digest) { }
    private final JdbcTemplate primary;
    private final JdbcTemplate authority;
    private final String issuer;
    private final ProductObservedTables tables;

    public ProductGrantWatermark(JdbcTemplate primary, JdbcTemplate authority, String issuer) {
        this.primary = ProductWatermarkJdbc.buffered(primary);
        this.authority = authority;
        this.issuer = issuer;
        this.tables = new ProductObservedTables(this.primary);
        assertCurrent();
    }

    public void assertCurrent() {
        currentGeneration();
    }

    public long currentGeneration() {
        State before = state();
        if (!"READY".equals(before.state()) || before.intent() != null)
            throw new IllegalStateException("Independent authorization intent is unresolved");
        String actual = historyDigest();
        State after = state();
        if (!before.equals(after) || !actual.equals(before.digest()))
            throw new IllegalStateException("Authorization history differs from independent high-water mark");
        return before.generation();
    }

    public void assertUnchanged(long generation) {
        if (currentGeneration() != generation)
            throw new IllegalStateException("Authorization changed during the protected response");
    }

    public JdbcTemplate independentDatabase() { return authority; }

    javax.sql.DataSource mainDataSource() { return primary.getDataSource(); }

    static final class OwnedChange {
        private final ProductGrantWatermark owner;
        private final State expected;
        private final Thread thread = Thread.currentThread();
        private boolean active = true;
        private OwnedChange(ProductGrantWatermark owner, State expected) {
            this.owner = owner; this.expected = expected;
        }
        void requireCurrent(ProductGrantWatermark expectedOwner) {
            if (!active || thread != Thread.currentThread() || owner != expectedOwner
                    || !expected.equals(owner.state()))
                throw new IllegalStateException("Current owned authorization intent required");
        }
    }

    public <T> T change(Supplier<T> operation) {
        return changeOwned(owned -> operation.get());
    }

    <T> T changeOwned(Function<OwnedChange, T> operation) {
        java.util.Objects.requireNonNull(operation);
        assertCurrent();
        State before = state();
        String intent = UUID.randomUUID().toString();
        int claimed = authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=? "
                        + "WHERE issuer=? AND generation=? AND state='READY' AND intent_id IS NULL AND history_sha=?",
                intent, issuer, before.generation(), before.digest());
        if (claimed != 1) throw new IllegalStateException("Concurrent authorization change");
        // The primary operation commits before the independent authority advances. If either step
        // fails, PENDING remains and no request can use a potentially stale grant.
        T result;
        OwnedChange owned = new OwnedChange(this,
                new State(before.generation(), "PENDING", intent, before.digest()));
        try {
            try { result = operation.apply(owned); }
            finally { owned.active = false; }
        } catch (RuntimeException failed) {
            // Only a provably unchanged primary state can release a rejected command.
            if (before.digest().equals(historyDigest())) {
                authority.update("UPDATE id2_authority_watermark SET state='READY',intent_id=NULL "
                                + "WHERE issuer=? AND generation=? AND state='PENDING' AND intent_id=? "
                                + "AND history_sha=?", issuer, before.generation(), intent, before.digest());
            }
            throw failed;
        }
        String digest = historyDigest();
        int completed = authority.update("UPDATE id2_authority_watermark SET generation=?,state='READY',"
                        + "intent_id=NULL,history_sha=? WHERE issuer=? AND generation=? AND state='PENDING' "
                        + "AND intent_id=? AND history_sha=?",
                before.generation() + 1, digest, issuer, before.generation(), intent, before.digest());
        if (completed != 1) throw new IllegalStateException("Independent authorization commit failed");
        assertCurrent();
        return result;
    }

    private State state() {
        List<State> rows = authority.query("SELECT generation,state,intent_id,history_sha "
                        + "FROM id2_authority_watermark WHERE issuer=?",
                (rs, row) -> new State(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), issuer);
        if (rows.size() != 1) throw new IllegalStateException("Independent authorization authority is absent");
        return rows.getFirst();
    }

    private String historyDigest() {
        boolean workload = workloadTablePresent();
        return ProductGrantHistoryBatch.digest(primary, issuer, workload,
                workload && tablePresent("CON3_WORKER_DELEGATION"));
    }

    private boolean workloadTablePresent() {
        return tablePresent("ID2_WORKLOAD");
    }

    private boolean tablePresent(String name) {
        return tables.present(name);
    }

}
