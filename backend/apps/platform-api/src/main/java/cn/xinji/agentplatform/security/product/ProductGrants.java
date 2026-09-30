package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional grant projection; callers must authenticate the independent maintenance identity. */
public final class ProductGrants {
    public record Scope(String issuer, String subject, String organization, String domain, String taskScope,
                        String dataScope, String resourceType, String resourceId, long resourceVersion,
                        String action, String purpose) { }
    public record Receipt(String grantId, long grantVersion, long epoch, String status) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ProductGrants(JdbcTemplate db, TransactionTemplate transactions, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Receipt issue(Scope scope, Instant expiresAt, long expectedEpoch, String maintainer,
                         String requestId, String requestSha) {
        require(scope, maintainer, requestId, requestSha);
        if ("SOURCE".equalsIgnoreCase(scope.resourceType())
                || "SOURCE_SCOPE".equalsIgnoreCase(scope.resourceType()))
            throw new IllegalArgumentException("Source grants require a current task source scope");
        return issueExact(scope, expiresAt, expectedEpoch, maintainer, requestId, requestSha, false, false);
    }

    Receipt issueWorkload(String issuer, String subject, String organization, String taskId,
            Instant expiresAt, long expectedEpoch, String maintainer, String requestId, String requestSha) {
        if (blank(issuer) || blank(subject) || blank(organization) || blank(taskId)
                || blank(maintainer) || blank(requestId) || requestSha == null
                || !requestSha.matches("[0-9a-fA-F]{64}") || expiresAt == null
                || !expiresAt.isAfter(clock.instant()))
            throw new IllegalArgumentException("Exact workload task grant required");
        return transactions.execute(status -> {
        List<String> locked = db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), taskId);
        if (locked.size() != 1) throw new IllegalStateException("Workload task unavailable");
        List<Scope> current = db.query("SELECT t.authorization_version,s.scope_id,s.scope_version "
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
                (rs, row) -> new Scope(issuer, subject, organization, "CONSULTATION", taskId,
                        rs.getString(2) + ":" + rs.getLong(3), "TASK", taskId, rs.getLong(1),
                        "WRITE", "TASK_WORK"), issuer, subject, taskId, organization,
                Timestamp.from(expiresAt), Timestamp.from(expiresAt));
        if (current.size() != 1) throw new IllegalStateException("Workload or task scope unavailable");
        return issueExact(current.getFirst(), expiresAt, expectedEpoch, maintainer,
                requestId, requestSha, false, true);
        });
    }

    Receipt issueSourceScope(Scope scope, Instant expiresAt, long expectedEpoch,
            String maintainer, String requestId, String requestSha, ProductSourceWatermark watermark) {
        require(scope, maintainer, requestId, requestSha);
        if (watermark == null) throw new IllegalArgumentException("Independent source authority is required");
        if (!"CONSULTATION".equals(scope.domain()) || !"SOURCE_SCOPE".equals(scope.resourceType())
                || !("QUERY".equals(scope.action()) && "SOURCE_QUERY".equals(scope.purpose())
                    || "DISPLAY".equals(scope.action()) && "SOURCE_DISPLAY".equals(scope.purpose())))
            throw new IllegalArgumentException("Only exact consultation source actions can be issued");
        List<String> sourceIds = db.queryForList("SELECT source_id FROM con4_task_source_scope "
                + "WHERE scope_id=? AND scope_version=?", String.class,
                scope.resourceId(), scope.resourceVersion());
        if (sourceIds.size() != 1) throw new IllegalStateException("Source scope is unavailable");
        var current = watermark.current(sourceIds.getFirst());
        if (!"ACTIVE".equals(current.status())) throw new IllegalStateException("Source is withdrawn");
        Receipt issued = issueExact(scope, expiresAt, expectedEpoch, maintainer, requestId, requestSha, true, false);
        watermark.assertUnchanged(sourceIds.getFirst(), current.epoch());
        return issued;
    }

    private Receipt issueExact(Scope scope, Instant expiresAt, long expectedEpoch,
            String maintainer, String requestId, String requestSha, boolean sourceScope, boolean workload) {
        if (expiresAt == null || !expiresAt.isAfter(clock.instant()) || expectedEpoch < 0)
            throw new IllegalArgumentException("A future expiry and current epoch are required");
        return transactions.execute(status -> {
            if (sourceScope) requireCurrentSourceScope(scope, expiresAt);
            Receipt replay = replay(requestId, requestSha, "ISSUE");
            if (replay != null) return replay;
            List<String> subjects = db.query("SELECT status FROM id2_subject WHERE issuer=? AND subject_id=? "
                            + (workload ? "AND subject_kind='SERVICE' AND password_hash IS NULL "
                                    : "AND password_hash IS NOT NULL ") + "FOR UPDATE",
                    (rs, row) -> rs.getString(1), scope.issuer(), scope.subject());
            if (subjects.size() != 1 || !"ACTIVE".equals(subjects.getFirst()))
                throw new IllegalStateException("Subject is not active");
            Epoch epoch = lockEpoch(scope);
            if (epoch == null) {
                if (expectedEpoch != 0) throw new IllegalStateException("Grant epoch changed");
            } else if (epoch.version() != expectedEpoch || epoch.grantId() != null) {
                throw new IllegalStateException("Grant epoch changed or current grant exists");
            }
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            long next = expectedEpoch + 1;
            db.update("INSERT INTO id2_grant(grant_id,issuer,subject_id,organization,domain_code,task_scope,data_scope,"
                            + "resource_type,resource_id,resource_version,action_code,purpose,grant_generation,"
                            + "issued_at,expires_at,status,grant_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, scope.issuer(), scope.subject(), scope.organization(), scope.domain(),
                    scope.taskScope(), scope.dataScope(),
                    scope.resourceType(), scope.resourceId(), scope.resourceVersion(), scope.action(), scope.purpose(),
                    next, Timestamp.from(now), Timestamp.from(expiresAt), "ACTIVE", 1);
            if (epoch == null) {
                db.update("INSERT INTO id2_current_epoch(issuer,subject_id,organization,domain_code,resource_type,resource_id,"
                                + "action_code,purpose,authorization_epoch,source_epoch,current_grant_id) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", scope.issuer(), scope.subject(), scope.organization(),
                        scope.domain(),
                        scope.resourceType(), scope.resourceId(), scope.action(), scope.purpose(), next, 0, id);
            } else {
                int changed = db.update("UPDATE id2_current_epoch SET authorization_epoch=?,current_grant_id=? "
                                + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code=? AND resource_type=? "
                                + "AND resource_id=? AND action_code=? AND purpose=? AND authorization_epoch=?",
                        next, id, scope.issuer(), scope.subject(), scope.organization(), scope.domain(), scope.resourceType(),
                        scope.resourceId(), scope.action(), scope.purpose(), expectedEpoch);
                if (changed != 1) throw new IllegalStateException("Grant epoch changed");
            }
            event(id, "ISSUE", 0, 1, next, maintainer, requestId, requestSha, now);
            return new Receipt(id, 1, next, "ACTIVE");
        });
    }

    private void requireCurrentSourceScope(Scope scope, Instant expiresAt) {
        if (!scope.dataScope().equals(scope.resourceId() + ":" + scope.resourceVersion()))
            throw new IllegalArgumentException("Source scope version does not match the grant");
        String use = "QUERY".equals(scope.action()) ? "QUERY" : "DISPLAY";
        List<Boolean> rows = db.query("SELECT tg.data_scope,ds.scope_id,ds.scope_version,"
                        + "tg.grant_generation,tc.authorization_epoch FROM con4_task_source_scope s "
                        + "JOIN con4_task_source_current sc ON sc.scope_id=s.scope_id "
                        + "AND sc.scope_version=s.scope_version AND sc.task_id=s.task_id "
                        + "AND sc.source_id=s.source_id JOIN con3_task t ON t.task_id=s.task_id "
                        + "AND t.organization_id=s.organization_id "
                        + "JOIN con3_organization o ON o.organization_id=s.organization_id "
                        + "JOIN con3_data_scope ds ON ds.task_id=t.task_id "
                        + "AND ds.organization_id=t.organization_id AND ds.kind='TASK_METADATA' "
                        + "JOIN id2_grant tg ON tg.issuer=? AND tg.subject_id=? "
                        + "AND tg.organization=s.organization_id AND tg.domain_code='CONSULTATION' "
                        + "AND tg.task_scope=s.task_id AND tg.resource_type='TASK' "
                        + "AND tg.resource_id=s.task_id AND tg.resource_version=t.authorization_version "
                        + "AND tg.action_code='READ' AND tg.purpose='TASK_WORK' AND tg.status='ACTIVE' "
                        + "JOIN id2_current_epoch tc ON tc.current_grant_id=tg.grant_id "
                        + "JOIN con4_source_current c ON c.source_id=s.source_id "
                        + "AND c.contract_version=s.contract_version AND c.decision_id=s.decision_id "
                        + "JOIN con4_source_admission a ON a.decision_id=s.decision_id "
                        + "JOIN con4_source_field_use f ON f.decision_id=a.decision_id "
                        + "WHERE s.scope_id=? AND s.scope_version=? AND s.task_id=? "
                        + "AND s.organization_id=? AND sc.status='ACTIVE' AND s.status='ACTIVE' "
                        + "AND o.status='ACTIVE' AND t.version=s.task_version "
                        + "AND c.status='ACTIVE' AND c.source_epoch=s.source_epoch "
                        + "AND c.row_version=s.source_row_version AND f.use_code=? "
                        + "AND s.expires_at>=? AND t.expires_at>=? AND a.valid_until>=? "
                        + "AND f.valid_until>=? AND tg.expires_at>=?",
                (rs, row) -> rs.getString(1).equals(rs.getString(2) + ":" + rs.getLong(3))
                        && rs.getLong(4) == rs.getLong(5), scope.issuer(), scope.subject(),
                scope.resourceId(), scope.resourceVersion(), scope.taskScope(), scope.organization(),
                use, Timestamp.from(expiresAt),
                Timestamp.from(expiresAt), Timestamp.from(expiresAt), Timestamp.from(expiresAt),
                Timestamp.from(expiresAt));
        if (rows.stream().noneMatch(Boolean::booleanValue))
            throw new IllegalStateException("Task, source scope or field use is not current");
    }

    public Receipt revoke(String grantId, long expectedVersion, String maintainer,
                          String requestId, String requestSha) {
        if (blank(grantId) || blank(maintainer) || blank(requestId) || blank(requestSha)
                || expectedVersion < 1) throw new IllegalArgumentException("Invalid revocation request");
        return transactions.execute(status -> {
            Receipt replay = replay(requestId, requestSha, "REVOKE");
            if (replay != null) return replay;
            List<Scope> matches = db.query("SELECT issuer,subject_id,organization,domain_code,task_scope,data_scope,resource_type,"
                            + "resource_id,resource_version,action_code,purpose FROM id2_grant WHERE grant_id=?",
                    (rs, row) -> new Scope(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getLong(9), rs.getString(10), rs.getString(11)), grantId);
            if (matches.size() != 1) throw new IllegalStateException("Grant unavailable");
            Scope scope = matches.getFirst();
            Epoch epoch = lockEpoch(scope);
            if (epoch == null || !grantId.equals(epoch.grantId()))
                throw new IllegalStateException("Grant is not current");
            int changed = db.update("UPDATE id2_grant SET status='REVOKED',grant_version=grant_version+1 "
                            + "WHERE grant_id=? AND status='ACTIVE' AND grant_version=?", grantId, expectedVersion);
            if (changed != 1) throw new IllegalStateException("Grant version changed");
            changed = db.update("UPDATE id2_current_epoch SET authorization_epoch=?,current_grant_id=NULL "
                            + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code=? AND resource_type=? "
                            + "AND resource_id=? AND action_code=? AND purpose=? AND authorization_epoch=? "
                            + "AND current_grant_id=?", epoch.version() + 1, scope.issuer(), scope.subject(),
                    scope.organization(), scope.domain(), scope.resourceType(), scope.resourceId(), scope.action(), scope.purpose(),
                    epoch.version(), grantId);
            if (changed != 1) throw new IllegalStateException("Grant epoch changed");
            event(grantId, "REVOKE", expectedVersion, expectedVersion + 1, epoch.version() + 1, maintainer,
                    requestId, requestSha, clock.instant());
            return new Receipt(grantId, expectedVersion + 1, epoch.version() + 1, "REVOKED");
        });
    }

    private Epoch lockEpoch(Scope scope) {
        List<Epoch> rows = db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch "
                        + "WHERE issuer=? AND subject_id=? AND organization=? AND domain_code=? AND resource_type=? "
                        + "AND resource_id=? AND action_code=? AND purpose=? FOR UPDATE",
                (rs, row) -> new Epoch(rs.getLong(1), rs.getString(2)), scope.issuer(), scope.subject(),
                scope.organization(), scope.domain(), scope.resourceType(), scope.resourceId(), scope.action(), scope.purpose());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Receipt replay(String requestId, String requestSha, String operation) {
        List<Receipt> rows = db.query("SELECT e.request_sha,e.grant_id,e.after_version,e.operation,e.after_epoch,"
                        + "g.status,c.current_grant_id,c.authorization_epoch FROM id2_grant_event e "
                        + "JOIN id2_grant g ON g.grant_id=e.grant_id JOIN id2_current_epoch c "
                        + "ON c.issuer=g.issuer AND c.subject_id=g.subject_id AND c.organization=g.organization "
                        + "AND c.domain_code=g.domain_code "
                        + "AND c.resource_type=g.resource_type AND c.resource_id=g.resource_id "
                        + "AND c.action_code=g.action_code AND c.purpose=g.purpose WHERE e.request_id=?",
                (rs, row) -> {
                    if (!requestSha.equals(rs.getString(1)) || !operation.equals(rs.getString(4)))
                        throw new IllegalStateException("Request id conflict");
                    if ("ISSUE".equals(operation) && (!"ACTIVE".equals(rs.getString(6))
                            || !rs.getString(2).equals(rs.getString(7)) || rs.getLong(5) != rs.getLong(8)))
                        throw new IllegalStateException("Replayed grant is no longer current");
                    return new Receipt(rs.getString(2), rs.getLong(3), rs.getLong(5),
                            "REVOKE".equals(rs.getString(4)) ? "REVOKED" : "ACTIVE");
                }, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void event(String grantId, String operation, long before, long after, long epoch, String maintainer,
                       String requestId, String requestSha, Instant now) {
        db.update("INSERT INTO id2_grant_event(event_id,request_id,grant_id,operation,before_version,"
                        + "after_version,after_epoch,maintainer_ref,request_sha,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), requestId, grantId, operation, before, after, epoch, maintainer,
                requestSha, Timestamp.from(now));
    }

    private static void require(Scope scope, String maintainer, String requestId, String requestSha) {
        if (scope == null || blank(scope.issuer()) || blank(scope.subject()) || blank(scope.organization())
                || blank(scope.domain())
                || blank(scope.taskScope()) || blank(scope.dataScope()) || blank(scope.resourceType())
                || blank(scope.resourceId()) || blank(scope.action()) || blank(scope.purpose())
                || scope.resourceVersion() < 1 || blank(maintainer) || blank(requestId)
                || requestSha == null || !requestSha.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Exact scope and authenticated maintenance request required");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Epoch(long version, String grantId) { }
}
