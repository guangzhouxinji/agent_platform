package cn.xinji.agentplatform.security.product;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** One transport round trip and sort; canonical history fields and ordering remain unchanged. */
final class ProductGrantHistoryBatch {
    private static final int MAX_FIELDS = 15;
    private static final int[] TIME_SECTIONS = {1, 4, 6, 9};
    private static final int[] TIME_FIELDS = {13, 4, 8, 12};
    // V032/V046/V047: original text fields <= 300 characters; numeric fields are BIGINT.
    // Keep timestamp fields in separate original-type columns: DM8 VARCHAR trims zeroes.
    static final List<String> QUERIES = List.of(
            "SELECT e.event_id,e.request_id,e.grant_id,e.operation,e.before_version,e.after_version,e.after_epoch,e.maintainer_ref,e.request_sha"
                    + " FROM id2_grant_event e JOIN id2_grant g ON g.grant_id=e.grant_id WHERE g.issuer=?"
                    + " ORDER BY e.event_id",
            "SELECT grant_id,subject_id,organization,domain_code,task_scope,data_scope,resource_type,resource_id,resource_version,action_code,purpose,grant_generation,expires_at,status,grant_version"
                    + " FROM id2_grant WHERE issuer=?"
                    + " ORDER BY grant_id",
            "SELECT subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose,authorization_epoch,source_epoch,current_grant_id"
                    + " FROM id2_current_epoch WHERE issuer=?"
                    + " ORDER BY subject_id,organization,domain_code,resource_type,resource_id,action_code,purpose",
            "SELECT e.event_id,e.subject_id,e.before_version,e.after_version,e.actor_ref"
                    + " FROM id2_subject_event e WHERE e.issuer=? AND e.operation='DISABLE'"
                    + " ORDER BY e.event_id",
            "SELECT s.subject_id,s.status,s.credential_version,s.disabled_at"
                    + " FROM id2_subject s WHERE s.issuer=? AND EXISTS (SELECT 1 FROM id2_subject_event e WHERE e.issuer=s.issuer AND e.subject_id=s.subject_id AND e.operation='DISABLE')"
                    + " ORDER BY s.subject_id",
            "SELECT w.subject_id,s.status,s.credential_version,s.subject_kind,s.password_hash"
                    + " FROM id2_workload w JOIN id2_subject s ON s.issuer=w.issuer AND s.subject_id=w.subject_id WHERE w.issuer=?"
                    + " ORDER BY w.subject_id",
            "SELECT subject_id,certificate_sha,organization,workload_kind,allowed_purpose,status,version,expires_at"
                    + " FROM id2_workload WHERE issuer=?"
                    + " ORDER BY subject_id",
            "SELECT e.event_id,e.subject_id,e.operation,e.before_version,e.after_version,e.maintainer_ref,e.certificate_sha"
                    + " FROM id2_workload_event e WHERE e.issuer=?"
                    + " ORDER BY e.event_id",
            "SELECT r.request_id,r.request_sha,r.subject_id,r.operation,r.result_version"
                    + " FROM id2_workload_request r WHERE r.issuer=?"
                    + " ORDER BY r.request_id",
            "SELECT delegation_id,task_id,organization_id,data_scope,authorization_version,human_subject,service_subject,human_grant_id,service_grant_id,status,version,expires_at"
                    + " FROM con3_worker_delegation WHERE human_issuer=? AND service_issuer=?"
                    + " ORDER BY delegation_id",
            "SELECT e.event_id,e.delegation_id,e.operation,e.before_version,e.after_version,e.actor_ref"
                    + " FROM con3_worker_delegation_event e JOIN con3_worker_delegation d ON d.delegation_id=e.delegation_id WHERE d.human_issuer=?"
                    + " ORDER BY e.event_id",
            "SELECT r.request_id,r.request_sha,r.operation,r.delegation_id,r.result_version"
                    + " FROM con3_worker_delegation_receipt r JOIN con3_worker_delegation d ON d.delegation_id=r.delegation_id WHERE d.human_issuer=?"
                    + " ORDER BY r.request_id");

    private record Plan(String sql, int parameters) { }
    private static final Plan BASE = plan(5), WORKLOAD = plan(9), DELEGATION = plan(12);
    private static final int[][] COLUMNS = columns();

    private ProductGrantHistoryBatch() { }

    static String digest(JdbcTemplate db, String issuer, boolean workload, boolean delegation) {
        int sections = workload ? (delegation ? 12 : 9) : 5;
        Plan plan = sections == 5 ? BASE : sections == 9 ? WORKLOAD : DELEGATION;
        Object[] parameters = java.util.Collections.nCopies(plan.parameters(), issuer).toArray();
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            var framing = new ProductHistoryDigestFraming(hash);
            // Section2 has a seven-text-field PK; every other section has a unique first
            // text key under its issuer filter. The single sort preserves each original ORDER BY.
            ProductPatentSearchTransportObservation.measure(
                    ProductPatentSearchTransportObservation.Detail.GRANT_HISTORY_SQL_AND_FETCH, () -> {
            db.query(plan.sql(), rs -> {
                int section = rs.getInt(1);
                int fields = rs.getInt(3);
                if (section < 0 || section >= sections || fields < 1 || fields > MAX_FIELDS)
                    throw new IllegalStateException("Invalid authorization history shape");
                for (int field = 1; field <= fields; field++) {
                    String value = rs.getString(COLUMNS[section][field]);
                    framing.add(value);
                }
            }, parameters);
            return null;
            });
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Plan plan(int sections) {
        List<String> parts = new ArrayList<>();
        int parameters = 0;
        for (int section = 0; section < sections; section++) {
            String query = QUERIES.get(section);
            parts.add(part(section, query));
            parameters += (int) query.chars().filter(c -> c == '?').count();
        }
        return new Plan(String.join(" UNION ALL ", parts)
                + " ORDER BY section_order,h1,h2,h3,h4,h5,h6,h7", parameters);
    }

    private static int[][] columns() {
        int[][] columns = new int[QUERIES.size()][MAX_FIELDS + 1];
        for (int section = 0; section < columns.length; section++)
            for (int field = 1; field <= MAX_FIELDS; field++) columns[section][field] = field + 3;
        for (int time = 0; time < TIME_SECTIONS.length; time++)
            columns[TIME_SECTIONS[time]][TIME_FIELDS[time]] = 19 + time;
        return columns;
    }

    private static String part(int section, String query) {
        int from = query.indexOf(" FROM ");
        int order = query.lastIndexOf(" ORDER BY ");
        String[] fields = query.substring(7, from).split(",");
        StringBuilder sql = new StringBuilder("SELECT ").append(section)
                .append(" section_order,0 row_order,")
                .append(fields.length).append(" field_count");
        for (int field = 0; field < MAX_FIELDS; field++) {
            sql.append(",CAST(").append(field < fields.length ? fields[field] : "NULL")
                    .append(" AS VARCHAR(2048)) h").append(field + 1);
        }
        for (int time = 0; time < TIME_SECTIONS.length; time++) {
            sql.append(',').append(section == TIME_SECTIONS[time]
                    ? fields[TIME_FIELDS[time] - 1] : "CAST(NULL AS TIMESTAMP)")
                    .append(" original_timestamp_").append(time);
        }
        return sql.append(query, from, order).toString();
    }
}
