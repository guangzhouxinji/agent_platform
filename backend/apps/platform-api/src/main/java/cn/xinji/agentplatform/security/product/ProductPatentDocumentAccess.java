package cn.xinji.agentplatform.security.product;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Exact current source authority; callers separately enforce Task actor and field-use admission. */
final class ProductPatentDocumentAccess {
    record Authority(String scopeId, long scopeVersion, long taskVersion, String grantId,
                     long grantGeneration) { }
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final Clock clock;
    private final String issuer;
    ProductPatentDocumentAccess(JdbcTemplate db, ProductGrantWatermark watermark, Clock clock, String issuer) {
        this.db = db; this.watermark = watermark; this.clock = clock; this.issuer = issuer;
    }
    Authority require(String taskId, ProductSourceAvailability.Active source, Jwt jwt) {
        return requireExact(taskId, source, jwt, "DISPLAY", "SOURCE_DISPLAY");
    }
    Authority requireComparison(String taskId, ProductSourceAvailability.Active source, Jwt jwt) {
        return requireExact(taskId, source, jwt, "COMPARE", "COMPARISON");
    }
    private Authority requireExact(String taskId, ProductSourceAvailability.Active source, Jwt jwt,
            String action, String purpose) {
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString()))
            throw new IllegalStateException("Source display identity unavailable");
        return requireSubject(taskId,source,jwt.getSubject(),action,purpose);
    }
    Authority requireDelegatedDisplay(String taskId, ProductSourceAvailability.Active source,
            ProductComparisonWorkerTaskAuthority.Authorized actor) {
        return requireDelegated(taskId,source,actor,"DISPLAY","SOURCE_DISPLAY");
    }
    Authority requireDelegatedComparison(String taskId, ProductSourceAvailability.Active source,
            ProductComparisonWorkerTaskAuthority.Authorized actor) {
        return requireDelegated(taskId,source,actor,"COMPARE","COMPARISON");
    }
    Authority requireDelegatedModelInput(String taskId,ProductSourceAvailability.Active source,
            ProductComparisonWorkerTaskAuthority.Authorized actor) {
        return requireDelegated(taskId,source,actor,"COMPARE","MODEL_INPUT");
    }
    /** Deadline for the exact already-authorized scope; never a new permission or frozen authority. */
    java.time.Instant modelInputUntil(String taskId, ProductSourceAvailability.Active source,
            ProductComparisonWorkerTaskAuthority.Authorized actor) {
        var before=requireDelegatedModelInput(taskId,source,actor);
        var deadlines=db.query("SELECT g.expires_at,s.expires_at,t.expires_at FROM id2_grant g "
                + "JOIN con4_task_source_scope s ON s.scope_id=g.resource_id AND s.scope_version=g.resource_version "
                + "JOIN con3_task t ON t.task_id=s.task_id WHERE g.grant_id=? AND g.grant_generation=? "
                + "AND s.scope_id=? AND s.scope_version=? AND s.task_id=? AND t.version=?",
                (rs,row)->java.util.List.of(rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant(),rs.getTimestamp(3).toInstant()),
                before.grantId(),before.grantGeneration(),before.scopeId(),before.scopeVersion(),taskId,before.taskVersion());
        if(deadlines.size()!=1 || !before.equals(requireDelegatedModelInput(taskId,source,actor)))
            throw new IllegalStateException("Current delegated model input deadline changed");
        var until=deadlines.getFirst().stream().min(java.time.Instant::compareTo).orElseThrow();
        if(actor.expiresAt().isBefore(until))until=actor.expiresAt();
        if(!until.isAfter(clock.instant()))throw new IllegalStateException("Current delegated model input deadline expired");
        return until;
    }
    private Authority requireDelegated(String taskId,ProductSourceAvailability.Active source,
            ProductComparisonWorkerTaskAuthority.Authorized actor,String action,String purpose) {
        if (actor==null || !taskId.equals(actor.taskId()) || !actor.expiresAt().isAfter(clock.instant()))
            throw new IllegalStateException("Current delegated source identity required");
        watermark.assertUnchanged(actor.grantGeneration());
        var result=requireSubject(taskId,source,actor.humanSubject(),action,purpose);
        if (result.taskVersion()!=actor.taskVersion()) throw new IllegalStateException("Delegated source Task changed");
        watermark.assertUnchanged(actor.grantGeneration());
        return result;
    }
    private Authority requireSubject(String taskId,ProductSourceAvailability.Active source,String subject,
            String action,String purpose) {
        long generation = watermark.currentGeneration();
        List<Authority> rows = db.query("SELECT s.scope_id,s.scope_version,s.task_version,g.grant_id,g.grant_generation "
                + "FROM con4_task_source_scope s JOIN con4_task_source_current sc ON sc.task_id=s.task_id "
                + "AND sc.source_id=s.source_id AND sc.scope_id=s.scope_id AND sc.scope_version=s.scope_version "
                + "JOIN con3_task t ON t.task_id=s.task_id AND t.organization_id=s.organization_id "
                + "AND t.version=s.task_version JOIN id2_grant g ON g.resource_id=s.scope_id "
                + "AND g.resource_version=s.scope_version AND g.data_scope=s.scope_id || ':' || s.scope_version "
                + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                + "AND c.authorization_epoch=g.grant_generation "
                + "WHERE s.task_id=? AND s.source_id=? AND s.contract_version=? AND s.decision_id=? "
                + "AND s.source_epoch=? AND s.source_row_version=? AND s.status='ACTIVE' AND sc.status='ACTIVE' "
                + "AND s.expires_at>? AND t.expires_at>? AND g.issuer=? AND g.subject_id=? "
                + "AND g.organization=s.organization_id AND g.task_scope=s.task_id "
                + "AND g.domain_code='CONSULTATION' AND g.resource_type='SOURCE_SCOPE' "
                + "AND g.action_code=? AND g.purpose=? AND g.status='ACTIVE' AND g.expires_at>?",
                (rs, row) -> new Authority(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5)),
                taskId, source.sourceId(), source.contractVersion(), source.decisionId(), source.epoch(),
                source.rowVersion(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
                issuer, subject, action, purpose, Timestamp.from(clock.instant()));
        watermark.assertUnchanged(generation);
        if (rows.size() != 1) throw new IllegalStateException("Current source " + action + " grant unavailable" + ("MODEL_INPUT".equals(purpose)?" / MODEL_INPUT":""));
        return rows.getFirst();
    }
}
