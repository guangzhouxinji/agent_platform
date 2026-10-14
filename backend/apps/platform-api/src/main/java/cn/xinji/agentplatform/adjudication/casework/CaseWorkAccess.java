package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Source;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.*;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Only a verified JWT and live exact ADJUDICATION case grant can act. Never consults CON/ENF grants. */
public final class CaseWorkAccess implements Access {
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final CaseSourceAuthority sources;
    private final Jwt jwt;
    private final String issuer;
    private final Clock clock;
    private long sourceGeneration;
    public CaseWorkAccess(JdbcTemplate db, ProductGrantWatermark watermark, CaseSourceAuthority sources,
                          Jwt verifiedJwt, String issuer, Clock clock) {
        this.db = db; this.watermark = watermark; this.sources = sources; this.jwt = verifiedJwt; this.issuer = issuer; this.clock = clock;
    }
    @Override public String actor() { validIdentity(); return issuer + "#" + jwt.getSubject(); }
    @Override public String identity() { return actor(); }
    @Override public long generation() { sourceGeneration = sources.generation(); return watermark.currentGeneration(); }
    @Override public void assertUnchanged(long generation) { watermark.assertUnchanged(generation); sources.assertUnchanged(sourceGeneration); }
    @Override public void sources(Scope scope, List<Source> originals, String use) { sources.require(scope, originals, use); }
    @Override public void require(Scope scope, String action) {
        validIdentity(); long generation = watermark.currentGeneration();
        Integer allowed = db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "AND c.issuer=g.issuer AND c.subject_id=g.subject_id AND c.organization=g.organization AND c.domain_code=g.domain_code "
                        + "AND c.resource_type=g.resource_type AND c.resource_id=g.resource_id AND c.action_code=g.action_code AND c.purpose=g.purpose "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + "JOIN id2_session s ON s.issuer=g.issuer AND s.subject_id=g.subject_id "
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.domain_code='ADJUDICATION' "
                        + "AND g.task_scope=? AND g.data_scope=? AND g.resource_type='ADJ_CASE' AND g.resource_id=? "
                        + "AND g.resource_version=1 AND g.action_code=? AND g.purpose='CASE_AUXILIARY' "
                        + "AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE' AND u.subject_kind='HUMAN' "
                        + "AND s.sid=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP",
                Integer.class, issuer, jwt.getSubject(), scope.organization(), scope.caseId(), scope.dataScope(), scope.caseId(), action,
                jwt.getClaimAsString("sid"), jwt.getId());
        watermark.assertUnchanged(generation);
        if (allowed == null || allowed != 1) throw new SecurityException("CURRENT_EXACT_CASE_GRANT_REQUIRED");
    }
    private void validIdentity() {
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getId() == null
                || jwt.getClaimAsString("sid") == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
            throw new SecurityException("VERIFIED_LIVE_IDENTITY_REQUIRED");
    }
}
