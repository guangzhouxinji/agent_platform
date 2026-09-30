package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Requires the delegating human's current, exact source query grant. */
final class ProductWorkerSourceGrant {
    private final JdbcTemplate db;
    private final String issuer;

    ProductWorkerSourceGrant(JdbcTemplate db, String issuer) {
        this.db = db;
        this.issuer = issuer;
    }

    void require(String human, String organization, String taskId, String sourceId,
            long taskVersion, long sourceEpoch, long sourceRowVersion, Instant now) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope s "
                + "JOIN con4_task_source_current sc ON sc.task_id=s.task_id "
                + "AND sc.source_id=s.source_id AND sc.scope_id=s.scope_id "
                + "AND sc.scope_version=s.scope_version "
                + "JOIN id2_grant g ON g.resource_id=s.scope_id "
                + "AND g.resource_version=s.scope_version "
                + "AND g.data_scope=s.scope_id || ':' || s.scope_version "
                + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                + "AND c.authorization_epoch=g.grant_generation "
                + "WHERE s.task_id=? AND s.organization_id=? AND s.source_id=? "
                + "AND s.task_version=? AND s.source_epoch=? AND s.source_row_version=? "
                + "AND s.status='ACTIVE' AND sc.status='ACTIVE' AND s.expires_at>? "
                + "AND g.issuer=? AND g.subject_id=? AND g.organization=? "
                + "AND g.domain_code='CONSULTATION' AND g.task_scope=? "
                + "AND g.resource_type='SOURCE_SCOPE' AND g.action_code='QUERY' "
                + "AND g.purpose='SOURCE_QUERY' AND g.status='ACTIVE' AND g.expires_at>?",
                Integer.class, taskId, organization, sourceId, taskVersion, sourceEpoch,
                sourceRowVersion, Timestamp.from(now), issuer, human, organization, taskId,
                Timestamp.from(now));
        if (count == null || count != 1)
            throw new IllegalStateException("Worker source query grant is not current");
    }
}
