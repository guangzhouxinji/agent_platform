package cn.xinji.agentplatform.security.product;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only recovery admission, not a Grant repair or an authorization decision. */
final class ProductGrantRecoveryEvidence {
    record Evidence(String format, String code, String issuer, Long generation, String intentId,
                    String recordedHistorySha, String observedHistorySha, boolean observationsStable,
                    boolean repairAuthorized, boolean mutationPerformed) { }
    static final class Refused extends IllegalStateException {
        private final Evidence evidence;
        Refused(Evidence evidence) {
            super("GRANT_RECOVERY_ADMISSION:" + evidence.code());
            this.evidence = evidence;
        }
        Evidence evidence() { return evidence; }
    }
    private record State(Long generation, String status, String intent, String digest) { }
    private record Observation(State state, String failure) { }
    private final JdbcTemplate primary;
    private final JdbcTemplate authority;
    private final String issuer;
    private final ProductObservedTables tables;

    ProductGrantRecoveryEvidence(JdbcTemplate primary, JdbcTemplate authority, String issuer) {
        if (primary == null || authority == null || issuer == null || issuer.isBlank() || issuer.length() > 300)
            throw new IllegalArgumentException("Exact configured Grant recovery connections and issuer required");
        // The configured Issuer is public identity metadata, never a credential-bearing URL.
        try {
            var uri = java.net.URI.create(issuer);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Public configured HTTPS Issuer required for recovery admission");
        }
        this.primary = ProductWatermarkJdbc.buffered(primary);
        this.authority = authority;
        this.issuer = issuer;
        this.tables = new ProductObservedTables(this.primary);
    }

    Evidence requireReady() {
        Evidence evidence = inspect();
        if (!"READY_CURRENT".equals(evidence.code())) throw new Refused(evidence);
        return evidence;
    }

    Evidence inspect() {
        Observation before = observe();
        if (before.failure() != null) return evidence(before.failure(), before.state(), null, false);
        State state = before.state();
        if (!valid(state)) return evidence("AUTHORITY_INVALID", state, null, false);
        String first;
        try { first = historyDigest(); }
        catch (RuntimeException unavailable) { return evidence("HISTORY_UNAVAILABLE", state, null, false); }
        Observation middle = observe();
        if (middle.failure() != null || !state.equals(middle.state()))
            return evidence("OBSERVATION_CHANGED", state, first, false);
        String second;
        try { second = historyDigest(); }
        catch (RuntimeException unavailable) { return evidence("HISTORY_UNAVAILABLE", state, null, false); }
        Observation after = observe();
        if (after.failure() != null || !state.equals(after.state()) || !first.equals(second))
            return evidence("OBSERVATION_CHANGED", state, second, false);
        // Equal repeated observations are not an atomic cross-connection proof or permission to repair.
        if ("PENDING".equals(state.status()))
            return evidence(state.digest().equals(second) ? "PENDING_HISTORY_UNCHANGED"
                    : "PENDING_HISTORY_CHANGED", state, second, true);
        return evidence(state.digest().equals(second) ? "READY_CURRENT"
                : "READY_HISTORY_MISMATCH", state, second, true);
    }

    private Evidence evidence(String code, State state, String observed, boolean stable) {
        return new Evidence("GRANT_RECOVERY_ADMISSION/v1", code, issuer,
                state == null ? null : state.generation(), state == null ? null : state.intent(),
                state == null ? null : state.digest(), observed, stable, false, false);
    }

    private Observation observe() {
        try {
            List<State> rows = authority.query("SELECT generation,state,intent_id,history_sha "
                            + "FROM id2_authority_watermark WHERE issuer=?",
                    (rs, row) -> {
                        long generation = rs.getLong(1);
                        return new State(rs.wasNull() ? null : generation, rs.getString(2),
                                rs.getString(3), rs.getString(4));
                    }, issuer);
            if (rows.isEmpty()) return new Observation(null, "AUTHORITY_ABSENT");
            if (rows.size() != 1) return new Observation(null, "AUTHORITY_INVALID");
            return new Observation(rows.getFirst(), null);
        } catch (RuntimeException unavailable) {
            // Driver/SQL messages may contain connection material. No raw cause leaves this reader.
            return new Observation(null, "AUTHORITY_UNAVAILABLE");
        }
    }

    private static boolean valid(State state) {
        if (state.generation() == null || state.generation() < 0 || state.digest() == null
                || !state.digest().matches("[0-9a-fA-F]{64}")) return false;
        return "READY".equals(state.status()) ? state.intent() == null
                : "PENDING".equals(state.status()) && state.intent() != null && !state.intent().isBlank();
    }

    private String historyDigest() {
        boolean workload = tables.present("ID2_WORKLOAD");
        return ProductGrantHistoryBatch.digest(primary, issuer, workload,
                workload && tables.present("CON3_WORKER_DELEGATION"));
    }
}