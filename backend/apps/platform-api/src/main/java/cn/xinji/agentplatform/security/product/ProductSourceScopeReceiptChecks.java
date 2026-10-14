package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reconcile each actually issued purpose grant; absent QUERY is not authorization for QUERY. */
final class ProductSourceScopeReceiptChecks {
    static boolean current(JdbcTemplate db, String issuer, ProductSourceScopeGrants.Input input,
            ProductSourceScopeGrants.Receipt receipt, Instant now) {
        if (receipt.queryGrantId() == null && receipt.displayGrantId() == null) return false;
        return grant(db, issuer, input, receipt, receipt.queryGrantId(), "QUERY", "SOURCE_QUERY", now)
                && grant(db, issuer, input, receipt, receipt.displayGrantId(), "DISPLAY", "SOURCE_DISPLAY", now);
    }
    private static boolean grant(JdbcTemplate db, String issuer, ProductSourceScopeGrants.Input input,
            ProductSourceScopeGrants.Receipt receipt, String id, String action, String purpose, Instant now) {
        if (id == null) return true;
        Integer valid = db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch e "
                + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                + "AND e.issuer=g.issuer AND e.subject_id=g.subject_id AND e.organization=g.organization "
                + "AND e.domain_code=g.domain_code AND e.resource_type=g.resource_type AND e.resource_id=g.resource_id "
                + "AND e.action_code=g.action_code AND e.purpose=g.purpose "
                + "JOIN id2_subject m ON m.issuer=g.issuer AND m.subject_id=g.subject_id "
                + "JOIN con3_task t ON t.task_id=g.task_scope AND t.organization_id=g.organization "
                + "WHERE g.grant_id=? AND g.issuer=? AND g.subject_id=? AND g.organization=t.organization_id "
                + "AND g.domain_code='CONSULTATION' AND g.task_scope=? AND g.resource_type='SOURCE_SCOPE' "
                + "AND g.resource_id=? AND g.resource_version=? AND g.data_scope=? "
                + "AND g.action_code=? AND g.purpose=? AND g.status='ACTIVE' AND m.status='ACTIVE' "
                + "AND g.expires_at>? AND t.version=? AND t.expires_at>?", Integer.class,
                id, issuer, input.memberSubject(), input.taskId(), receipt.scopeId(), receipt.scopeVersion(),
                receipt.scopeId() + ":" + receipt.scopeVersion(), action, purpose, Timestamp.from(now),
                input.taskVersion(), Timestamp.from(now));
        return valid != null && valid == 1;
    }
    private ProductSourceScopeReceiptChecks() { }
}
