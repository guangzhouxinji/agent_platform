package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** DM8 adapter for the immutable first consultation task resource. */
public final class JdbcProductTaskDirectory implements ProductTaskDirectory {
    private final JdbcTemplate db;
    private final Clock clock;

    public JdbcProductTaskDirectory(JdbcTemplate db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override public Optional<Task> find(String id) {
        List<Task> rows = db.query("SELECT t.task_id,t.organization_id,t.owner_issuer,t.owner_subject,t.state,"
                        + "t.version,t.authorization_version,s.scope_id,s.scope_version,t.expires_at FROM con3_task t "
                        + "JOIN con3_organization o ON o.organization_id=t.organization_id "
                        + "JOIN con3_data_scope s ON s.task_id=t.task_id AND s.organization_id=t.organization_id "
                        + "WHERE t.task_id=? AND o.status='ACTIVE' AND s.kind='TASK_METADATA'",
                (rs, row) -> new Task(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getLong(9), rs.getTimestamp(10).toInstant()), id);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    @Override public Optional<Receipt> receipt(String requestId) {
        return lookupReceipt("con3_initial_receipt", requestId);
    }

    @Override public Optional<Receipt> creationReceipt(String requestId) {
        return lookupReceipt("con3_task_create_receipt", requestId);
    }

    private Optional<Receipt> lookupReceipt(String table, String requestId) {
        List<Receipt> rows = db.query("SELECT r.request_sha,r.task_id,r.grant_id,g.status,c.current_grant_id,"
                        + "g.grant_generation,c.authorization_epoch,g.organization,g.task_scope,g.data_scope,"
                        + "g.resource_type,g.resource_id,g.resource_version,g.action_code,g.purpose,"
                        + "u.status,g.expires_at "
                        + "FROM " + table + " r "
                        + "JOIN id2_grant g ON g.grant_id=r.grant_id "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + "JOIN id2_current_epoch c ON c.issuer=g.issuer AND c.subject_id=g.subject_id "
                        + "AND c.organization=g.organization AND c.domain_code=g.domain_code "
                        + "AND c.resource_type=g.resource_type AND c.resource_id=g.resource_id "
                        + "AND c.action_code=g.action_code AND c.purpose=g.purpose WHERE r.request_id=?",
                (rs, row) -> {
                    Task task = find(rs.getString(2)).orElse(null);
                    boolean current = task != null && "ACTIVE".equals(rs.getString(4))
                            && rs.getString(3).equals(rs.getString(5)) && rs.getLong(6) == rs.getLong(7)
                            && task.organization().equals(rs.getString(8))
                            && task.id().equals(rs.getString(9)) && task.dataScope().equals(rs.getString(10))
                            && "TASK".equals(rs.getString(11)) && task.id().equals(rs.getString(12))
                            && task.authorizationVersion() == rs.getLong(13) && "READ".equals(rs.getString(14))
                            && "TASK_WORK".equals(rs.getString(15))
                            && "ACTIVE".equals(rs.getString(16))
                            && rs.getTimestamp(17).toInstant().isAfter(clock.instant());
                    return new Receipt(rs.getString(1), task, rs.getString(3), current);
                }, requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override public Task insertInitial(Initial input) {
        String taskId = UUID.randomUUID().toString();
        String scopeId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        db.update("INSERT INTO con3_organization(organization_id,name,status,version,created_at) "
                        + "VALUES(?,?,'ACTIVE',1,?)", input.organization(), input.name(), Timestamp.from(now));
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,"
                        + "expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                        + "VALUES(?,?,?,?,'AWAITING_GOAL',1,?,?,?,?,?)", taskId, input.organization(),
                input.issuer(), input.subject(), Timestamp.from(input.expiresAt()), input.retentionRef(),
                input.retentionVersion(), input.retentionEvidenceSha(), Timestamp.from(now));
        db.update("INSERT INTO con3_data_scope(scope_id,task_id,organization_id,scope_version,kind) "
                        + "VALUES(?,?,?,1,'TASK_METADATA')", scopeId, taskId, input.organization());
        db.update("INSERT INTO con3_initial_task(organization_id,task_id) VALUES(?,?)",
                input.organization(), taskId);
        db.update("INSERT INTO con3_event(event_id,task_id,operation,actor_ref,version,request_sha,occurred_at) "
                        + "VALUES(?,?,'INITIAL_CREATE',?,1,?,?)", UUID.randomUUID().toString(), taskId,
                input.actor(), input.requestSha(), Timestamp.from(now));
        return new Task(taskId, input.organization(), input.issuer(), input.subject(),
                "AWAITING_GOAL", 1, 1, scopeId, 1, input.expiresAt());
    }

    @Override public void recordReceipt(Initial input, Task task, String grantId) {
        db.update("INSERT INTO con3_initial_receipt(request_id,request_sha,organization_id,task_id,"
                        + "scope_id,grant_id,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                input.requestId(), input.requestSha(), input.organization(), task.id(), task.scopeId(), grantId);
    }

    @Override public Task insert(Creation input) {
        String taskId = UUID.randomUUID().toString();
        String scopeId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        db.update("INSERT INTO con3_task(task_id,organization_id,owner_issuer,owner_subject,state,version,"
                        + "expires_at,retention_ref,retention_version,retention_evidence_sha,created_at) "
                        + "VALUES(?,?,?,?,'AWAITING_GOAL',1,?,?,?,?,?)", taskId, input.organization(),
                input.issuer(), input.subject(), Timestamp.from(input.expiresAt()), input.retentionRef(),
                input.retentionVersion(), input.retentionEvidenceSha(), Timestamp.from(now));
        db.update("INSERT INTO con3_data_scope(scope_id,task_id,organization_id,scope_version,kind) "
                + "VALUES(?,?,?,1,'TASK_METADATA')", scopeId, taskId, input.organization());
        db.update("INSERT INTO con3_event(event_id,task_id,operation,actor_ref,version,request_sha,occurred_at) "
                        + "VALUES(?,?,'TASK_CREATED',?,1,?,?)", UUID.randomUUID().toString(), taskId,
                input.subject(), input.requestSha(), Timestamp.from(now));
        return new Task(taskId, input.organization(), input.issuer(), input.subject(),
                "AWAITING_GOAL", 1, 1, scopeId, 1, input.expiresAt());
    }

    @Override public void recordCreationReceipt(Creation input, Task task, String grantId) {
        db.update("INSERT INTO con3_task_create_receipt(request_id,request_sha,actor_issuer,actor_subject,"
                        + "organization_id,task_id,scope_id,grant_id,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)", input.requestId(), input.requestSha(),
                input.issuer(), input.subject(), input.organization(), task.id(), task.scopeId(), grantId);
    }
}
