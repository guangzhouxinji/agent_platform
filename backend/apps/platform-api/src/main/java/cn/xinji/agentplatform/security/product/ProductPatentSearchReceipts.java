package cn.xinji.agentplatform.security.product;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads immutable source coverage with the same committed result on replay and pagination. */
final class ProductPatentSearchReceipts {
    private final JdbcTemplate db;
    ProductPatentSearchReceipts(JdbcTemplate db) { this.db=db; }
    /** Caller has already checked current Task/Source authority, including execution replay. */
    ProductPatentSearchExecution.Receipt read(String resultSetId) {
        new ProductHistoricalResultIsolation(db).require(resultSetId);
        return readOriginalEvidence(resultSetId);
    }
    /** Internal immutable evidence for isolation/recovery only; not a product consumption path. */
    ProductPatentSearchExecution.Receipt readOriginalEvidence(String resultSetId) {
        var sources = db.query("SELECT s.source_id,s.contract_version,s.decision_id,s.source_epoch,"
                + "s.status,s.error_code FROM con4_search_source s JOIN con4_result_set r "
                + "ON r.attempt_id=s.attempt_id WHERE r.result_set_id=? ORDER BY s.source_id",
                (rs,row)->new ProductPatentSearchSourceBatch.Outcome(rs.getString(1),rs.getString(2),
                        rs.getString(3),rs.getLong(4),rs.getString(5),rs.getString(6)),resultSetId);
        List<ProductPatentSearchExecution.Receipt> rows = db.query(
                "SELECT a.attempt_id,a.fencing_token,r.result_set_id,a.method_version,"
                + "COUNT(m.member_id),r.candidate_count,r.truncated,r.result_sha,r.confidence_status,"
                + "r.confidence_rule_version,r.confidence_reason,a.status,r.total_reliability,r.digest_version FROM con4_result_set r "
                + "JOIN con4_search_attempt a ON a.attempt_id=r.attempt_id "
                + "LEFT JOIN con4_result_member m ON m.result_set_id=r.result_set_id "
                + "WHERE r.result_set_id=? GROUP BY a.attempt_id,a.fencing_token,r.result_set_id,"
                + "a.method_version,r.candidate_count,r.truncated,r.result_sha,r.confidence_status,"
                + "r.confidence_rule_version,r.confidence_reason,a.status,r.total_reliability,r.digest_version",
                (rs,row)->new ProductPatentSearchExecution.Receipt(rs.getString(1),rs.getLong(2),
                        rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getInt(7)==1,
                        rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),
                        rs.getString(12),rs.getString(13),List.copyOf(sources),rs.getString(14)),resultSetId);
        if(rows.size()!=1 || sources.isEmpty()) throw new IllegalStateException("Committed search receipt missing");
        return rows.getFirst();
    }
}

