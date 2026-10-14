package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** A verified capture is usable only under a current, exact field-use decision. */
public final class ProductSourceAvailability {
    public record Active(String sourceId, String contractVersion, String sourceVersion,
                         String decisionId, long epoch, long rowVersion, String manifestRef,
                         String manifestSha, String recordsSha) { }

    private static final Set<String> USES = Set.of("QUERY", "SAVE", "INDEX", "DISPLAY",
            "DOWNLOAD", "MODEL", "SHARE", "COMPARISON");
    private final JdbcTemplate db;
    private final ProductSourceWatermark watermark;
    private final Clock clock;

    public ProductSourceAvailability(JdbcTemplate db, ProductSourceWatermark watermark, Clock clock) {
        this.db = db;
        this.watermark = watermark;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_source_verification", Integer.class);
    }

    public Active require(String sourceId, String field, String use) {
        if (field == null || field.isBlank())
            throw new IllegalArgumentException("Exact source field and use required");
        return requireFields(sourceId, Set.of(field), use);
    }

    /** Current contract/admission/field-use deadline; the snapshot itself never authorizes future use. */
    Instant useUntil(String sourceId,String field,String use) {
        var before=require(sourceId,field,use);
        var rows=db.query("SELECT k.valid_until,a.valid_until,f.valid_until FROM con4_source_contract k "
                + "JOIN con4_source_admission a ON a.source_id=k.source_id AND a.contract_version=k.contract_version "
                + "JOIN con4_source_field_use f ON f.decision_id=a.decision_id "
                + "WHERE k.source_id=? AND k.contract_version=? AND a.decision_id=? AND f.field_code=? AND f.use_code=?",
                (rs,row)->java.util.List.of(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant()),
                sourceId,before.contractVersion(),before.decisionId(),field,use);
        if(rows.size()!=1 || !before.equals(require(sourceId,field,use)))throw new IllegalStateException("Current source use deadline changed");
        var until=rows.getFirst().stream().min(Instant::compareTo).orElseThrow();
        if(!until.isAfter(clock.instant()))throw new IllegalStateException("Current source use deadline expired");
        return until;
    }
    /** One exact field matrix under full before/after authority checks, never an authorization cache. */
    public Active requireFields(String sourceId, Set<String> requested, String use) {
        if (sourceId == null || sourceId.isBlank() || requested == null || requested.isEmpty()
                || requested.stream().anyMatch(field -> field == null || field.isBlank())
                || use == null || !USES.contains(use))
            throw new IllegalArgumentException("Exact source field and use required");
        Set<String> fields = new java.util.TreeSet<>(requested);
        ProductSourceWatermark.Current current = watermark.current(sourceId);
        if (!"ACTIVE".equals(current.status()))
            throw new IllegalStateException("Source is not active");
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(sourceId); parameters.addAll(fields); parameters.add(use);
        for (int i = 0; i < 3; i++) parameters.add(Timestamp.from(clock.instant()));
        String placeholders = String.join(",", java.util.Collections.nCopies(fields.size(), "?"));
        record Field(String code, Active active) { }
        List<Field> rows = db.query("SELECT f.field_code,c.source_id,c.contract_version,k.source_version,"
                        + "c.decision_id,c.source_epoch,c.row_version,v.manifest_ref,v.manifest_sha,"
                        + "v.records_sha FROM con4_source_current c "
                        + "JOIN con4_source_contract k ON k.source_id=c.source_id "
                        + "AND k.contract_version=c.contract_version "
                        + "JOIN con4_source_admission a ON a.decision_id=c.decision_id "
                        + "AND a.source_id=c.source_id AND a.contract_version=c.contract_version "
                        + "JOIN con4_source_product_decision d ON d.decision_id=a.decision_id "
                        + "JOIN con4_source_verification v ON v.source_id=c.source_id "
                        + "AND v.contract_version=c.contract_version AND v.source_version=k.source_version "
                        + "JOIN con4_source_field_use f ON f.decision_id=c.decision_id "
                        + "WHERE c.source_id=? AND c.status='ACTIVE' AND k.status='ACTIVE' "
                        + "AND k.row_version=2 AND v.candidate_row_version=1 "
                        + "AND f.field_code IN (" + placeholders + ") AND f.use_code=? AND k.valid_until>? "
                        + "AND a.valid_until>? AND f.valid_until>? "
                        + "AND d.rights_evidence_status<>'WITHDRAWN'",
                (rs, row) -> new Field(rs.getString(1), new Active(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getString(9), rs.getString(10))), parameters.toArray());
        Set<String> seen = new java.util.HashSet<>();
        if (rows.size() != fields.size() || rows.stream().anyMatch(row -> !seen.add(row.code()))
                || !seen.equals(fields)) throw new IllegalStateException("Source approval or field use is unavailable");
        Active active = rows.getFirst().active();
        if (rows.stream().anyMatch(row -> !active.equals(row.active()))
                || !current.contractVersion().equals(active.contractVersion())
                || !current.decisionId().equals(active.decisionId())
                || current.epoch() != active.epoch()
                || current.rowVersion() != active.rowVersion())
            throw new IllegalStateException("Source approval or field use is unavailable");
        watermark.assertUnchanged(sourceId, current.epoch());
        return active;
    }
}
