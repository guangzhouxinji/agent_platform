package cn.xinji.agentplatform.security.product;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Complete source history in one round trip/sort, retaining native CLOB, time and event order. */
final class ProductSourceHistoryBatch {
    record Snapshot(ProductSourceWatermark.Current current, String storedDigest, String digest) { }
    private record CurrentFacts(ProductSourceWatermark.Current current, String storedDigest) { }
    private static final int MAX_FIELDS = 18;
    private static final int[] TIME_SECTIONS = {1, 1, 2, 4, 5};
    private static final int[] TIME_FIELDS = {17, 18, 3, 11, 13};
    // Current source schema text fields <= 500 characters. declaration_json remains native CLOB.
    static final List<String> QUERIES = List.of(
            "SELECT source_id,contract_version,decision_id,source_epoch,status,row_version"
                    + " FROM con4_source_current WHERE source_id=?",
            "SELECT c.contract_version,c.source_version,c.declaration_json,c.request_sha,c.evidence_ref,c.evidence_sha,c.status,c.row_version,a.decision_id,a.rights_ref,a.rights_sha,a.capability_ref,a.capability_sha,a.responsible_party,a.maintainer_ref,a.coverage_sha,a.valid_until,a.decided_at"
                    + " FROM con4_source_current p JOIN con4_source_contract c ON c.source_id=p.source_id AND c.contract_version=p.contract_version JOIN con4_source_admission a ON a.decision_id=p.decision_id WHERE p.source_id=?",
            "SELECT f.field_code,f.use_code,f.valid_until"
                    + " FROM con4_source_current p JOIN con4_source_field_use f ON f.decision_id=p.decision_id WHERE p.source_id=? ORDER BY f.field_code,f.use_code",
            "SELECT d.decision_id,d.decision_version,d.decision_ref,d.decision_sha,d.rights_evidence_status,d.organization_id,d.task_id,d.scope_id,d.retention_ref,d.retention_version,d.retention_evidence_sha"
                    + " FROM con4_source_current p JOIN con4_source_product_decision d ON d.decision_id=p.decision_id WHERE p.source_id=?",
            "SELECT v.verification_id,v.source_version,v.candidate_row_version,v.manifest_ref,v.manifest_sha,v.records_sha,v.inventory_sha,v.record_count,v.raw_count,v.verifier_ref,v.verified_at"
                    + " FROM con4_source_verification v JOIN con4_source_current p ON p.source_id=v.source_id AND p.contract_version=v.contract_version WHERE p.source_id=?",
            "SELECT event_id,contract_version,decision_id,operation,before_epoch,after_epoch,before_version,after_version,intent_id,before_history_sha,actor_ref,request_sha,occurred_at"
                    + " FROM con4_source_authority_event WHERE source_id=? ORDER BY after_epoch,event_id");

    private ProductSourceHistoryBatch() { }

    static String digest(JdbcTemplate db, String sourceId, boolean productDecision) {
        return read(db, sourceId, productDecision).digest();
    }

    static Snapshot read(JdbcTemplate db, String sourceId, boolean productDecision) {
        List<String> parts = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        for (int section = 0; section < QUERIES.size(); section++) {
            if (section == 3 && !productDecision) continue;
            parts.add(part(section, QUERIES.get(section)));
            parameters.add(sourceId);
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            var framing = new ProductHistoryDigestFraming(hash);
            int[] rowCounts = new int[QUERIES.size()];
            CurrentFacts[] current = new CurrentFacts[1];
            // Only the authority-event segment needs a numeric key. Field uses retain
            // their two-text-field PK order; all remaining segments are singletons/unique IDs.
            db.query(String.join(" UNION ALL ", parts)
                    + " ORDER BY section_order,row_order,h1,h2", rs -> {
                int section = rs.getInt(1);
                int fields = rs.getInt(3);
                if (section < 0 || section >= rowCounts.length || fields < 1 || fields > MAX_FIELDS)
                    throw new IllegalStateException("Invalid source history shape");
                rowCounts[section]++;
                if (section == 0) {
                    if (rowCounts[section] != 1 || !sourceId.equals(rs.getString(4)))
                        throw new IllegalStateException("Source current history shape differs");
                    current[0] = new CurrentFacts(new ProductSourceWatermark.Current(
                            rs.getLong(7), rs.getString(8), rs.getLong(9), rs.getString(5), rs.getString(6)),
                            rs.getString(28));
                }
                for (int field = 1; field <= fields; field++) {
                    String value = rs.getString(field + 3);
                    if (section == 1 && field == 3) value = rs.getString(22);
                    for (int time = 0; time < TIME_SECTIONS.length; time++) {
                        if (section == TIME_SECTIONS[time] && field == TIME_FIELDS[time]) {
                            var timestamp = rs.getTimestamp(23 + time);
                            value = timestamp == null ? null : timestamp.toLocalDateTime().toString();
                        }
                    }
                    framing.add(value);
                }
            }, parameters.toArray());
            if (productDecision && rowCounts[0] != 0 && rowCounts[3] != 1)
                throw new IllegalStateException("Product-use decision is missing");
            return new Snapshot(current[0] == null ? null : current[0].current(),
                    current[0] == null ? null : current[0].storedDigest(),
                    HexFormat.of().formatHex(hash.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String part(int section, String query) {
        int from = query.indexOf(" FROM ");
        int order = query.lastIndexOf(" ORDER BY ");
        String[] fields = query.substring(7, from).split(",");
        StringBuilder sql = new StringBuilder("SELECT ").append(section)
                .append(" section_order,").append(section == 5 ? fields[5] : "0")
                .append(" row_order,").append(fields.length).append(" field_count");
        for (int field = 0; field < MAX_FIELDS; field++) {
            boolean clob = section == 1 && field == 2;
            sql.append(",CAST(").append(field < fields.length && !clob ? fields[field] : "NULL")
                    .append(" AS VARCHAR(2048)) h").append(field + 1);
        }
        sql.append(',').append(section == 1 ? fields[2] : "CAST(NULL AS CLOB)")
                .append(" original_clob");
        for (int time = 0; time < TIME_SECTIONS.length; time++) {
            sql.append(',').append(section == TIME_SECTIONS[time]
                    ? fields[TIME_FIELDS[time] - 1] : "CAST(NULL AS TIMESTAMP)")
                    .append(" original_timestamp_").append(time);
        }
        sql.append(",CAST(").append(section == 0 ? "history_sha" : "NULL")
                .append(" AS VARCHAR(64)) current_history_sha");
        return sql.append(query, from, order < 0 ? query.length() : order).toString();
    }
}
