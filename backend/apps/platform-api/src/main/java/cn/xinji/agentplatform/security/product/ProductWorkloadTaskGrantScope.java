package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current Task scope query shared only by the two fixed workload grant commands. */
final class ProductWorkloadTaskGrantScope {
    private ProductWorkloadTaskGrantScope() { }

    static ProductGrants.Scope current(JdbcTemplate db, String issuer, String subject,
            String organization, String taskId, Instant expiresAt, String action) {
        List<String> locked = db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), taskId);
        if (locked.size() != 1) throw new IllegalStateException("Workload task unavailable");
        List<ProductGrants.Scope> current = db.query("SELECT t.authorization_version,s.scope_id,s.scope_version "
                        + "FROM con3_task t JOIN con3_organization o "
                        + "ON o.organization_id=t.organization_id JOIN con3_data_scope s "
                        + "ON s.task_id=t.task_id AND s.organization_id=t.organization_id "
                        + "JOIN id2_workload w ON w.issuer=? AND w.subject_id=? "
                        + "AND w.organization=t.organization_id JOIN id2_subject u "
                        + "ON u.issuer=w.issuer AND u.subject_id=w.subject_id "
                        + "WHERE t.task_id=? AND t.organization_id=? AND t.state='IN_PROGRESS' "
                        + "AND o.status='ACTIVE' AND s.kind='TASK_METADATA' "
                        + "AND w.status='ACTIVE' AND w.allowed_purpose='TASK_WORK' "
                        + "AND w.workload_kind='AGENT' AND w.expires_at>=? "
                        + "AND u.status='ACTIVE' AND u.subject_kind='SERVICE' AND u.password_hash IS NULL "
                        + "AND t.expires_at>=?",
                (rs, row) -> new ProductGrants.Scope(issuer, subject, organization, "CONSULTATION", taskId,
                        rs.getString(2) + ":" + rs.getLong(3), "TASK", taskId, rs.getLong(1),
                        action, "TASK_WORK"), issuer, subject, taskId, organization,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt));
        if (current.size() != 1) throw new IllegalStateException("Workload or task scope unavailable");
        return current.getFirst();
    }
}
