package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** A verified capture is usable only under a current, exact field-use decision. */
public final class ProductSourceAvailability {
    public record Active(String sourceId, String contractVersion, String sourceVersion,
                         String decisionId, long epoch, long rowVersion, String manifestRef,
                         String manifestSha, String recordsSha) { }

    private static final Set<String> USES = Set.of("QUERY", "SAVE", "INDEX", "DISPLAY",
            "DOWNLOAD", "MODEL", "SHARE");
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
        if (sourceId == null || sourceId.isBlank() || field == null || field.isBlank()
                || use == null || !USES.contains(use))
            throw new IllegalArgumentException("Exact source field and use required");
        ProductSourceWatermark.Current current = watermark.current(sourceId);
        if (!"ACTIVE".equals(current.status()))
            throw new IllegalStateException("Source is not active");
        List<Active> rows = db.query("SELECT c.source_id,c.contract_version,k.source_version,"
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
                        + "AND f.field_code=? AND f.use_code=? AND k.valid_until>? "
                        + "AND a.valid_until>? AND f.valid_until>? "
                        + "AND d.rights_evidence_status<>'WITHDRAWN'",
                (rs, row) -> new Active(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)), sourceId, field, use,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        if (rows.size() != 1 || !current.contractVersion().equals(rows.getFirst().contractVersion())
                || !current.decisionId().equals(rows.getFirst().decisionId())
                || current.epoch() != rows.getFirst().epoch()
                || current.rowVersion() != rows.getFirst().rowVersion())
            throw new IllegalStateException("Source approval or field use is unavailable");
        watermark.assertUnchanged(sourceId, current.epoch());
        return rows.getFirst();
    }
}
