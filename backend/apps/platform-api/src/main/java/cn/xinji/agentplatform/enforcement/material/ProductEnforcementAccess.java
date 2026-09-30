package cn.xinji.agentplatform.enforcement.material;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Binds a verified JWT to exact enforcement grants and independent current state. */
public final class ProductEnforcementAccess implements MaterialAccess {
    private final JdbcTemplate business;
    private final JdbcTemplate authority;
    private final ProductGrantWatermark watermark;
    private final Jwt jwt;
    private final String issuer;
    private final Clock clock;

    public ProductEnforcementAccess(JdbcTemplate business, JdbcTemplate authority,
                                    ProductGrantWatermark watermark, Jwt jwt, String issuer, Clock clock) {
        this.business = business;
        this.authority = authority;
        this.watermark = watermark;
        this.jwt = jwt;
        this.issuer = issuer;
        this.clock = clock;
    }

    @Override public void require(String actor, String organization, String resourceType, String resourceId,
                                  long version, String action, String purpose) {
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || !actor.equals(jwt.getSubject()) || jwt.getId() == null
                || jwt.getClaimAsString("sid") == null || jwt.getClaimAsString("sid").isBlank()
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
            throw new SecurityException("Verified current identity required");
        long generation = watermark.currentGeneration();
        Integer grants = business.queryForObject("SELECT COUNT(*) FROM id2_grant g "
                        + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + "JOIN id2_session s ON s.issuer=g.issuer AND s.subject_id=g.subject_id "
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? "
                        + "AND g.domain_code='ENFORCEMENT' AND g.resource_type=? AND g.resource_id=? "
                        + "AND g.resource_version=? AND g.action_code=? AND g.purpose=? "
                        + "AND g.status='ACTIVE' AND u.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP "
                        + "AND s.sid=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP",
                Integer.class, issuer, actor, organization,
                "SOURCE".equals(resourceType) ? "ENFORCEMENT_SOURCE" : resourceType,
                resourceId, version, action,
                purpose, jwt.getClaimAsString("sid"), jwt.getId());
        watermark.assertUnchanged(generation);
        if (grants == null || grants != 1) throw new SecurityException("Current enforcement grant required");
    }

    @Override public void requireSource(String sourceId, String use, long epoch) {
        long generation = watermark.currentGeneration();
        Integer current = authority.queryForObject("SELECT COUNT(*) FROM enf3_source_watermark "
                        + "WHERE source_id=? AND source_epoch=? AND state='READY'", Integer.class,
                sourceId, epoch);
        Integer retained = authority.queryForObject("SELECT COUNT(*) FROM enf3_delete_watermark "
                        + "WHERE source_id=? AND source_epoch=? AND state='READY' AND valid_until>?",
                Integer.class, sourceId, epoch, Timestamp.from(clock.instant()));
        watermark.assertUnchanged(generation);
        if (current == null || current != 1 || retained == null || retained != 1)
            throw new SecurityException("Current source or retention use unavailable");
    }

    @Override public void prepareSource(String sourceId, long epoch) {
        watermark.assertCurrent();
        List<String> existing = authority.query("SELECT state FROM enf3_source_watermark "
                        + "WHERE source_id=? AND source_epoch=?", (rs, row) -> rs.getString(1), sourceId, epoch);
        if (existing.isEmpty()) {
            authority.update("INSERT INTO enf3_source_watermark(source_id,source_epoch,state) "
                    + "VALUES(?,?,'PENDING')", sourceId, epoch);
        } else if (!"PENDING".equals(existing.getFirst()) && !"READY".equals(existing.getFirst())) {
            throw new SecurityException("Withdrawn source cannot be reactivated");
        }
    }

    @Override public void activateSource(String sourceId, long epoch) {
        watermark.assertCurrent();
        int changed = authority.update("UPDATE enf3_source_watermark SET state='READY' "
                        + "WHERE source_id=? AND source_epoch=? AND state='PENDING'", sourceId, epoch);
        Integer ready = authority.queryForObject("SELECT COUNT(*) FROM enf3_source_watermark "
                + "WHERE source_id=? AND source_epoch=? AND state='READY'", Integer.class, sourceId, epoch);
        if (changed != 1 && (ready == null || ready != 1))
            throw new IllegalStateException("Independent source activation failed");
        Timestamp validUntil = business.queryForObject("SELECT expires_at FROM enf3_source WHERE source_id=?",
                Timestamp.class, sourceId);
        List<String> deletion = authority.query("SELECT state FROM enf3_delete_watermark "
                        + "WHERE source_id=? AND source_epoch=?", (rs, row) -> rs.getString(1), sourceId, epoch);
        if (deletion.isEmpty()) {
            authority.update("INSERT INTO enf3_delete_watermark(source_id,source_epoch,delete_epoch,state,valid_until) "
                    + "VALUES(?,?,0,'READY',?)", sourceId, epoch, validUntil);
        } else if (!"READY".equals(deletion.getFirst())) {
            throw new SecurityException("Deleted source cannot be reactivated");
        }
        requireSource(sourceId, "SOURCE_MANAGEMENT", epoch);
    }
}
