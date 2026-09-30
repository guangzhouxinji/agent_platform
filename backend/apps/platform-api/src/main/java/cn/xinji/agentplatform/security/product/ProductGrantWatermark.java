package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Independent authority: an unfinished cross-store write closes every protected request. */
public final class ProductGrantWatermark {
    private record State(long generation, String state, String intent, String digest) { }
    private final JdbcTemplate primary;
    private final JdbcTemplate authority;
    private final String issuer;

    public ProductGrantWatermark(JdbcTemplate primary, JdbcTemplate authority, String issuer) {
        this.primary = primary;
        this.authority = authority;
        this.issuer = issuer;
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

    public <T> T change(Supplier<T> operation) {
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
        try {
            result = operation.get();
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
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            primary.query("SELECT e.event_id,e.request_id,e.grant_id,e.operation,e.before_version,"
                            + "e.after_version,e.after_epoch,e.maintainer_ref,e.request_sha "
                            + "FROM id2_grant_event e JOIN id2_grant g ON g.grant_id=e.grant_id "
                            + "WHERE g.issuer=? ORDER BY e.event_id", rs -> {
                        for (int i = 1; i <= 9; i++) field(hash, rs.getString(i));
                    }, issuer);
            primary.query("SELECT grant_id,subject_id,organization,domain_code,task_scope,data_scope,"
                            + "resource_type,resource_id,resource_version,action_code,purpose,grant_generation,"
                            + "expires_at,status,grant_version FROM id2_grant WHERE issuer=? ORDER BY grant_id",
                    rs -> { for (int i = 1; i <= 15; i++) field(hash, rs.getString(i)); }, issuer);
            primary.query("SELECT subject_id,organization,domain_code,resource_type,resource_id,action_code,"
                            + "purpose,authorization_epoch,source_epoch,current_grant_id FROM id2_current_epoch "
                            + "WHERE issuer=? ORDER BY subject_id,organization,domain_code,resource_type,resource_id,"
                            + "action_code,purpose", rs -> {
                        for (int i = 1; i <= 10; i++) field(hash, rs.getString(i));
                    }, issuer);
            primary.query("SELECT e.event_id,e.subject_id,e.before_version,e.after_version,e.actor_ref "
                            + "FROM id2_subject_event e WHERE e.issuer=? AND e.operation='DISABLE' "
                            + "ORDER BY e.event_id", rs -> {
                        for (int i = 1; i <= 5; i++) field(hash, rs.getString(i));
                    }, issuer);
            primary.query("SELECT s.subject_id,s.status,s.credential_version,s.disabled_at "
                            + "FROM id2_subject s WHERE s.issuer=? AND EXISTS (SELECT 1 FROM id2_subject_event e "
                            + "WHERE e.issuer=s.issuer AND e.subject_id=s.subject_id AND e.operation='DISABLE') "
                            + "ORDER BY s.subject_id", rs -> {
                        for (int i = 1; i <= 4; i++) field(hash, rs.getString(i));
                    }, issuer);
            if (workloadTablePresent()) {
                primary.query("SELECT w.subject_id,s.status,s.credential_version,s.subject_kind,"
                                + "s.password_hash FROM id2_workload w JOIN id2_subject s "
                                + "ON s.issuer=w.issuer AND s.subject_id=w.subject_id "
                                + "WHERE w.issuer=? ORDER BY w.subject_id",
                        rs -> { for (int i = 1; i <= 5; i++) field(hash, rs.getString(i)); }, issuer);
                primary.query("SELECT subject_id,certificate_sha,organization,workload_kind,allowed_purpose,"
                                + "status,version,expires_at FROM id2_workload WHERE issuer=? ORDER BY subject_id",
                        rs -> { for (int i = 1; i <= 8; i++) field(hash, rs.getString(i)); }, issuer);
                primary.query("SELECT e.event_id,e.subject_id,e.operation,e.before_version,e.after_version,"
                                + "e.maintainer_ref,e.certificate_sha FROM id2_workload_event e "
                                + "WHERE e.issuer=? ORDER BY e.event_id",
                        rs -> { for (int i = 1; i <= 7; i++) field(hash, rs.getString(i)); }, issuer);
                primary.query("SELECT r.request_id,r.request_sha,r.subject_id,r.operation,"
                                + "r.result_version FROM id2_workload_request r "
                                + "WHERE r.issuer=? ORDER BY r.request_id",
                        rs -> { for (int i = 1; i <= 5; i++) field(hash, rs.getString(i)); }, issuer);
                if (tablePresent("CON3_WORKER_DELEGATION")) {
                    primary.query("SELECT delegation_id,task_id,organization_id,data_scope,"
                                    + "authorization_version,human_subject,service_subject,"
                                    + "human_grant_id,service_grant_id,status,version,expires_at "
                                    + "FROM con3_worker_delegation WHERE human_issuer=? "
                                    + "AND service_issuer=? ORDER BY delegation_id",
                            rs -> { for (int i = 1; i <= 12; i++) field(hash, rs.getString(i)); },
                            issuer, issuer);
                    primary.query("SELECT e.event_id,e.delegation_id,e.operation,e.before_version,"
                                    + "e.after_version,e.actor_ref FROM con3_worker_delegation_event e "
                                    + "JOIN con3_worker_delegation d ON d.delegation_id=e.delegation_id "
                                    + "WHERE d.human_issuer=? ORDER BY e.event_id",
                            rs -> { for (int i = 1; i <= 6; i++) field(hash, rs.getString(i)); }, issuer);
                    primary.query("SELECT r.request_id,r.request_sha,r.operation,r.delegation_id,"
                                    + "r.result_version FROM con3_worker_delegation_receipt r "
                                    + "JOIN con3_worker_delegation d ON d.delegation_id=r.delegation_id "
                                    + "WHERE d.human_issuer=? ORDER BY r.request_id",
                            rs -> { for (int i = 1; i <= 5; i++) field(hash, rs.getString(i)); }, issuer);
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private boolean workloadTablePresent() {
        return tablePresent("ID2_WORKLOAD");
    }

    private boolean tablePresent(String name) {
        try (var connection = primary.getDataSource().getConnection();
             var tables = connection.getMetaData().getTables(null, connection.getSchema(),
                     name, new String[] {"TABLE"})) {
            return tables.next();
        } catch (java.sql.SQLException unavailable) {
            throw new IllegalStateException("Authorization schema cannot be inspected", unavailable);
        }
    }

    private static void field(MessageDigest hash, String value) {
        String text = value == null ? "-1:" : value.length() + ":" + value;
        hash.update(text.getBytes(StandardCharsets.UTF_8));
    }
}
