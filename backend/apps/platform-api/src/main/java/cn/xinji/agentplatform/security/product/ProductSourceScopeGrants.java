package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Issues only task-bound source scopes under current task and independent grant authority. */
public final class ProductSourceScopeGrants {
    public record Input(String taskId, String memberSubject, String sourceId, long taskVersion,
                        long sourceRowVersion, Instant expiresAt, String requestId) { }
    public record Receipt(String scopeId, long scopeVersion, String queryGrantId, String displayGrantId) { }
    private record Scope(String id, long version, long taskVersion, long sourceEpoch,
                         long sourceRowVersion, String contractVersion, String decisionId,
                         String status, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductTaskDirectory tasks;
    private final ProductGrants grants;
    private final ProductGrantWatermark grantWatermark;
    private final ProductSourceWatermark sourceWatermark;
    private final ProductMaintenanceProof maintenance;
    private final Clock clock;
    private final String issuer;

    public ProductSourceScopeGrants(JdbcTemplate db, TransactionTemplate transactions,
            ProductTaskDirectory tasks, ProductGrants grants, ProductGrantWatermark grantWatermark,
            ProductSourceWatermark sourceWatermark, ProductMaintenanceProof maintenance,
            Clock clock, String issuer) {
        this.db = db;
        this.transactions = transactions;
        this.tasks = tasks;
        this.grants = grants;
        this.grantWatermark = grantWatermark;
        this.sourceWatermark = sourceWatermark;
        this.maintenance = maintenance;
        this.clock = clock;
        this.issuer = issuer;
    }

    public Receipt authorize(Input input, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SOURCES);
        if (!actor.equals(maintenance.require(request, ProductMaintenanceProof.Role.GRANTS)))
            throw new SecurityException("Maintenance principal changed");
        return authorizeVerified(input, actor,
                maintenance.requireOrganization(request, ProductMaintenanceProof.Role.SOURCES));
    }

    Receipt authorizeVerified(Input input, String actor, String organization) {
        if (input == null || blank(actor) || blank(organization) || blank(issuer)
                || blank(input.taskId()) || blank(input.memberSubject()) || blank(input.sourceId())
                || input.requestId() == null || !input.requestId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}")
                || input.taskVersion() < 1 || input.sourceRowVersion() < 1
                || input.expiresAt() == null || !input.expiresAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("Exact task and source grant request required");
        Instant expiresAt = input.expiresAt().truncatedTo(ChronoUnit.MILLIS);
        if (!expiresAt.isAfter(clock.instant()))
            throw new IllegalArgumentException("Grant expiry is too close to the current time");
        var task = tasks.find(input.taskId()).orElseThrow(() -> new IllegalStateException("Task unavailable"));
        var source = sourceWatermark.current(input.sourceId());
        if (!organization.equals(task.organization()) || task.version() != input.taskVersion()
                || !task.expiresAt().isAfter(clock.instant()) || !"ACTIVE".equals(source.status())
                || source.rowVersion() != input.sourceRowVersion())
            throw new IllegalStateException("Task or source version changed");
        Integer enabledForTask = db.queryForObject("SELECT COUNT(*) FROM con4_source_product_decision "
                + "WHERE decision_id=? AND organization_id=? AND task_id=? "
                + "AND rights_evidence_status<>'WITHDRAWN'", Integer.class,
                source.decisionId(), organization, input.taskId());
        if (enabledForTask == null || enabledForTask != 1)
            throw new IllegalStateException("Product source use is not enabled for this task");
        String requestSha = digest(actor, organization, input);
        grantWatermark.assertCurrent();
        Receipt replay = replay(input, requestSha);
        if (replay != null) {
            sourceWatermark.assertUnchanged(input.sourceId(), source.epoch());
            grantWatermark.assertCurrent();
            return replay;
        }
        Receipt result = grantWatermark.change(() -> transactions.execute(status -> {
            Receipt repeated = replay(input, requestSha);
            if (repeated != null) return repeated;
            Scope scope = currentScope(input.taskId(), input.sourceId());
            if (scope == null) scope = createScope(input, organization, task.version(), source, expiresAt);
            else if (scope.taskVersion() != task.version())
                scope = renewScope(input, organization, task.version(), source, expiresAt,
                        scope, actor, requestSha);
            if (!"ACTIVE".equals(scope.status()) || scope.taskVersion() != task.version()
                    || scope.sourceEpoch() != source.epoch()
                    || scope.sourceRowVersion() != source.rowVersion()
                    || !scope.contractVersion().equals(source.contractVersion())
                    || !scope.decisionId().equals(source.decisionId())
                    || scope.expiresAt().isBefore(expiresAt))
                throw new IllegalStateException("Source scope is not current");
            String dataScope = scope.id() + ":" + scope.version();
            long queryEpoch = availableEpoch(input.memberSubject(), organization, scope.id(),
                    "QUERY", "SOURCE_QUERY");
            var query = grants.issueSourceScope(new ProductGrants.Scope(issuer, input.memberSubject(),
                    organization, "CONSULTATION", input.taskId(), dataScope, "SOURCE_SCOPE", scope.id(),
                    scope.version(), "QUERY", "SOURCE_QUERY"), expiresAt, queryEpoch, actor,
                    input.requestId() + ":query", requestSha, sourceWatermark);
            String displayGrantId = null;
            Integer displays = db.queryForObject("SELECT COUNT(*) FROM con4_source_field_use "
                    + "WHERE decision_id=? AND use_code='DISPLAY' AND valid_until>=?", Integer.class,
                    source.decisionId(), Timestamp.from(expiresAt));
            if (displays != null && displays > 0) {
                long displayEpoch = availableEpoch(input.memberSubject(), organization, scope.id(),
                        "DISPLAY", "SOURCE_DISPLAY");
                displayGrantId = grants.issueSourceScope(new ProductGrants.Scope(issuer,
                        input.memberSubject(), organization, "CONSULTATION", input.taskId(), dataScope,
                        "SOURCE_SCOPE", scope.id(), scope.version(), "DISPLAY", "SOURCE_DISPLAY"),
                        expiresAt, displayEpoch, actor, input.requestId() + ":display", requestSha,
                        sourceWatermark).grantId();
            }
            String event = UUID.randomUUID().toString();
            db.update("INSERT INTO con4_task_source_event(event_id,scope_id,scope_version,operation,actor_ref,"
                    + "request_sha,occurred_at) VALUES(?,?,?,'GRANT',?,?,?)", event, scope.id(),
                    scope.version(), actor, requestSha, Timestamp.from(clock.instant()));
            db.update("INSERT INTO con4_task_source_request(request_id,request_sha,event_id,query_grant_id,"
                    + "display_grant_id,created_at) VALUES(?,?,?,?,?,?)", input.requestId(), requestSha,
                    event, query.grantId(), displayGrantId, Timestamp.from(clock.instant()));
            return new Receipt(scope.id(), scope.version(), query.grantId(), displayGrantId);
        }));
        sourceWatermark.assertUnchanged(input.sourceId(), source.epoch());
        grantWatermark.assertCurrent();
        return result;
    }

    private Scope createScope(Input input, String organization, long taskVersion,
            ProductSourceWatermark.Current source, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO con4_task_source_scope(scope_id,scope_version,task_id,organization_id,"
                        + "task_version,source_id,contract_version,decision_id,source_epoch,source_row_version,"
                        + "status,expires_at,created_at) VALUES(?,1,?,?,?,?,?,?,?,?,'ACTIVE',?,?)", id,
                input.taskId(), organization, taskVersion, input.sourceId(), source.contractVersion(),
                source.decisionId(), source.epoch(), source.rowVersion(), Timestamp.from(expiresAt),
                Timestamp.from(clock.instant()));
        db.update("INSERT INTO con4_task_source_current(task_id,source_id,scope_id,scope_version,row_version,"
                + "status) VALUES(?,?,?,1,1,'ACTIVE')", input.taskId(), input.sourceId(), id);
        return currentScope(input.taskId(), input.sourceId());
    }

    private record OldGrant(String id) { }
    private record Epoch(long version, String grantId) { }

    private long availableEpoch(String subject, String organization, String scopeId,
            String action, String purpose) {
        List<Epoch> rows = db.query("SELECT authorization_epoch,current_grant_id "
                        + "FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? "
                        + "AND domain_code='CONSULTATION' AND resource_type='SOURCE_SCOPE' "
                        + "AND resource_id=? AND action_code=? AND purpose=?",
                (rs, row) -> new Epoch(rs.getLong(1), rs.getString(2)),
                issuer, subject, organization, scopeId, action, purpose);
        if (rows.size() > 1 || !rows.isEmpty() && rows.getFirst().grantId() != null)
            throw new IllegalStateException("Existing source grant must be revoked first");
        return rows.isEmpty() ? 0 : rows.getFirst().version();
    }

    private Scope renewScope(Input input, String organization, long taskVersion,
            ProductSourceWatermark.Current source, Instant expiresAt, Scope old,
            String actor, String requestSha) {
        if (!"ACTIVE".equals(old.status()) || !old.contractVersion().equals(source.contractVersion())
                || !old.decisionId().equals(source.decisionId()) || old.sourceEpoch() != source.epoch()
                || old.sourceRowVersion() != source.rowVersion())
            throw new IllegalStateException("Source scope changed; explicit source reauthorization required");
        List<OldGrant> active = db.query("SELECT g.grant_id "
                        + "FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "AND c.authorization_epoch=g.grant_generation WHERE g.issuer=? "
                        + "AND g.organization=? AND g.task_scope=? AND g.resource_type='SOURCE_SCOPE' "
                        + "AND g.resource_id=? AND g.resource_version=? AND g.status='ACTIVE'",
                (rs, row) -> new OldGrant(rs.getString(1)),
                issuer, organization, input.taskId(), old.id(), old.version());
        for (OldGrant grant : active) {
            String revokeRequest = "scope-retire:" + UUID.nameUUIDFromBytes(
                    (input.requestId() + ":" + grant.id()).getBytes(StandardCharsets.UTF_8));
            grants.revoke(grant.id(), 1, actor, revokeRequest, requestSha);
        }
        if (db.update("UPDATE con4_task_source_scope SET status='REVOKED' WHERE scope_id=? "
                        + "AND scope_version=? AND status='ACTIVE'", old.id(), old.version()) != 1)
            throw new IllegalStateException("Source scope changed");
        db.update("INSERT INTO con4_task_source_event(event_id,scope_id,scope_version,operation,actor_ref,"
                        + "request_sha,occurred_at) VALUES(?,?,?,'REVOKE',?,?,?)",
                UUID.randomUUID().toString(), old.id(), old.version(), actor, requestSha,
                Timestamp.from(clock.instant()));
        long next = old.version() + 1;
        db.update("INSERT INTO con4_task_source_scope(scope_id,scope_version,task_id,organization_id,"
                        + "task_version,source_id,contract_version,decision_id,source_epoch,source_row_version,"
                        + "status,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                old.id(), next, input.taskId(), organization, taskVersion, input.sourceId(),
                source.contractVersion(), source.decisionId(), source.epoch(), source.rowVersion(),
                Timestamp.from(expiresAt), Timestamp.from(clock.instant()));
        if (db.update("UPDATE con4_task_source_current SET scope_version=?,row_version=row_version+1 "
                        + "WHERE task_id=? AND source_id=? AND scope_id=? AND scope_version=? "
                        + "AND status='ACTIVE'", next, input.taskId(), input.sourceId(), old.id(),
                old.version()) != 1) throw new IllegalStateException("Source scope pointer changed");
        return currentScope(input.taskId(), input.sourceId());
    }

    private Scope currentScope(String taskId, String sourceId) {
        List<Scope> rows = db.query("SELECT s.scope_id,s.scope_version,s.task_version,s.source_epoch,"
                        + "s.source_row_version,s.contract_version,s.decision_id,s.status,s.expires_at "
                        + "FROM con4_task_source_current c JOIN con4_task_source_scope s "
                        + "ON s.scope_id=c.scope_id AND s.scope_version=c.scope_version "
                        + "WHERE c.task_id=? AND c.source_id=? AND c.status='ACTIVE'",
                (rs, row) -> new Scope(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9).toInstant()), taskId, sourceId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Receipt replay(Input input, String requestSha) {
        List<Receipt> rows = db.query("SELECT r.request_sha,s.scope_id,s.scope_version,r.query_grant_id,"
                        + "r.display_grant_id FROM con4_task_source_request r "
                        + "JOIN con4_task_source_event e ON e.event_id=r.event_id "
                        + "JOIN con4_task_source_scope s ON s.scope_id=e.scope_id "
                        + "AND s.scope_version=e.scope_version "
                        + "JOIN con4_task_source_current sc ON sc.scope_id=s.scope_id "
                        + "AND sc.scope_version=s.scope_version AND sc.status='ACTIVE' "
                        + "JOIN id2_grant q ON q.grant_id=r.query_grant_id "
                        + "LEFT JOIN id2_grant d ON d.grant_id=r.display_grant_id "
                        + "JOIN id2_current_epoch qc ON qc.current_grant_id=q.grant_id "
                        + "LEFT JOIN id2_current_epoch dc ON dc.current_grant_id=d.grant_id "
                        + "JOIN id2_subject m ON m.issuer=q.issuer AND m.subject_id=q.subject_id "
                        + "WHERE r.request_id=? AND s.task_id=? AND s.source_id=? "
                        + "AND (r.display_grant_id IS NULL OR dc.current_grant_id IS NOT NULL)",
                (rs, row) -> {
                    if (!requestSha.equals(rs.getString(1)))
                        throw new IllegalStateException("Source grant receipt is no longer current");
                    return new Receipt(rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5));
                }, input.requestId(), input.taskId(), input.sourceId());
        if (!rows.isEmpty()) {
            Scope scope = currentScope(input.taskId(), input.sourceId());
            Receipt receipt = rows.getFirst();
            Integer valid = db.queryForObject("SELECT COUNT(*) FROM id2_grant q "
                    + "LEFT JOIN id2_grant d ON d.grant_id=? JOIN id2_subject m "
                    + "ON m.issuer=q.issuer AND m.subject_id=q.subject_id "
                    + "WHERE q.grant_id=? AND q.status='ACTIVE' "
                    + "AND (? IS NULL OR d.status='ACTIVE') "
                    + "AND m.status='ACTIVE' AND q.subject_id=? "
                    + "AND (? IS NULL OR d.subject_id=?) "
                    + "AND q.expires_at>? AND (? IS NULL OR d.expires_at>?)", Integer.class,
                    receipt.displayGrantId(), receipt.queryGrantId(), receipt.displayGrantId(),
                    input.memberSubject(), receipt.displayGrantId(), input.memberSubject(),
                    Timestamp.from(clock.instant()), receipt.displayGrantId(),
                    Timestamp.from(clock.instant()));
            if (scope == null || !scope.id().equals(receipt.scopeId())
                    || scope.version() != receipt.scopeVersion() || !"ACTIVE".equals(scope.status())
                    || !scope.expiresAt().isAfter(clock.instant()) || valid == null || valid != 1)
                throw new IllegalStateException("Source grant receipt is no longer current");
        }
        if (!rows.isEmpty()) return rows.getFirst();
        Integer existing = db.queryForObject("SELECT COUNT(*) FROM con4_task_source_request WHERE request_id=?",
                Integer.class, input.requestId());
        if (existing != null && existing != 0) throw new IllegalStateException("Source grant request conflict");
        return null;
    }

    private static String digest(String actor, String organization, Input input) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(actor, organization, input.taskId(), input.memberSubject(),
                    input.sourceId(), Long.toString(input.taskVersion()),
                    Long.toString(input.sourceRowVersion()), input.expiresAt().toString()))
                hash.update((field.length() + ":" + field).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
