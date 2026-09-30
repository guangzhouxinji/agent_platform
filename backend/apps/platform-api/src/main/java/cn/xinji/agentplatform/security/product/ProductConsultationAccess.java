package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Binds verified identity and current grants to domain-owned consultation resource facts. */
public final class ProductConsultationAccess {
    public record ResourceFacts(String organization, String taskId, String dataScope,
                                String resourceType, String resourceId, long resourceVersion) { }

    private record CurrentGrant(String organization, String taskScope, String dataScope,
                                String resourceType, String resourceId, long resourceVersion,
                                String action, String purpose, java.time.Instant expiresAt,
                                long authorizationEpoch, long sourceEpoch) { }

    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final Clock clock;

    public ProductConsultationAccess(JdbcTemplate db, ProductGrantWatermark watermark,
                                     String issuer, Clock clock) {
        this.db = db;
        this.watermark = watermark;
        this.issuer = issuer;
        this.clock = clock;
    }

    /** Caller supplies persisted domain facts, never client-submitted organization or scope. */
    public boolean permits(Jwt verifiedJwt, ResourceFacts resource, ProductAccessPolicy.Action action) {
        if (verifiedJwt == null || verifiedJwt.getIssuer() == null
                || !issuer.equals(verifiedJwt.getIssuer().toString()) || verifiedJwt.getSubject() == null
                || blank(verifiedJwt.getClaimAsString("sid")) || blank(verifiedJwt.getId())
                || resource == null || action == null || blank(resource.organization())
                || blank(resource.taskId()) || blank(resource.dataScope())
                || blank(resource.resourceType()) || blank(resource.resourceId())
                || resource.resourceVersion() < 1) return false;
        long generation = watermark.currentGeneration();
        List<CurrentGrant> rows = db.query("SELECT g.organization,g.task_scope,g.data_scope,g.resource_type,"
                        + "g.resource_id,g.resource_version,g.action_code,g.purpose,g.expires_at,"
                        + "c.authorization_epoch,c.source_epoch FROM id2_grant g "
                        + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + "JOIN id2_session s ON s.issuer=g.issuer AND s.subject_id=g.subject_id "
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.domain_code='CONSULTATION' "
                        + "AND g.organization=? AND g.task_scope=? AND g.data_scope=? "
                        + "AND g.resource_type=? AND g.resource_id=? AND g.resource_version=? "
                        + "AND g.action_code=? AND g.purpose='TASK_WORK' AND g.status='ACTIVE' "
                        + "AND u.status='ACTIVE' "
                        + "AND s.sid=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP "
                        + "AND g.expires_at>CURRENT_TIMESTAMP",
                (rs, row) -> new CurrentGrant(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getLong(10), rs.getLong(11)),
                issuer, verifiedJwt.getSubject(), resource.organization(), resource.taskId(),
                resource.dataScope(), resource.resourceType(), resource.resourceId(),
                resource.resourceVersion(), action.name(), verifiedJwt.getClaimAsString("sid"),
                verifiedJwt.getId());
        boolean allowed = rows.size() == 1 && decision(verifiedJwt, resource, action, rows.getFirst());
        watermark.assertUnchanged(generation);
        return allowed;
    }

    private boolean decision(Jwt jwt, ResourceFacts facts, ProductAccessPolicy.Action action, CurrentGrant grant) {
        var actor = new ProductAccessPolicy.Actor(issuer, jwt.getSubject(),
                ProductAccessPolicy.Kind.HUMAN, grant.organization());
        var resource = new ProductAccessPolicy.Resource("CONSULTATION", grant.resourceType(),
                grant.resourceId(), facts.organization(), grant.resourceVersion());
        var purpose = ProductAccessPolicy.Purpose.TASK_WORK;
        return grant.taskScope().equals(facts.taskId()) && grant.dataScope().equals(facts.dataScope())
                && action.name().equals(grant.action()) && purpose.name().equals(grant.purpose())
                && ProductAccessPolicy.permits(
                        new ProductAccessPolicy.Request(actor, resource, action, purpose, null, clock.instant()),
                        new ProductAccessPolicy.Evidence(
                                new ProductAccessPolicy.CurrentState(grant.authorizationEpoch(),
                                        grant.sourceEpoch(), true),
                                List.of(new ProductAccessPolicy.Grant(actor, resource, action, purpose,
                                        grant.expiresAt(), grant.authorizationEpoch())), null, List.of()));
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
