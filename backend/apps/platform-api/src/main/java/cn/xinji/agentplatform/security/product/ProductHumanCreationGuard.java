package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current HUMAN/CREATE authority for one normal creation; never grants a maintenance privilege. */
final class ProductHumanCreationGuard {
    private record Grant(String id, long epoch, long version, Instant until) { }
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ProductConsultationAccess access;
    private final JwtDecoder decoder;
    private final String issuer, token, organization;
    private final Clock clock;
    private final Instant expiry;
    private final ProductHumanIdentityLocks identities;
    private Jwt verified;
    private ProductHumanIdentityLocks.Identity identity;
    private Grant grant;
    private Long organizationVersion;

    ProductHumanCreationGuard(JdbcTemplate db, ProductGrantWatermark watermark, ProductConsultationAccess access,
            JwtDecoder decoder, String issuer, Clock clock, String token, String organization, Instant expiry) {
        if (db.getDataSource() == null || db.getDataSource() != watermark.mainDataSource()
                || db.getDataSource() != access.mainDataSource()) throw unavailable();
        this.db = db; this.watermark = watermark; this.access = access; this.decoder = decoder;
        this.issuer = issuer; this.clock = clock; this.token = Objects.requireNonNull(token);
        this.organization = Objects.requireNonNull(organization); this.expiry = Objects.requireNonNull(expiry);
        identities = new ProductHumanIdentityLocks(db, issuer, clock);
    }

    Jwt requireReady() {
        long generation = watermark.currentGeneration();
        Jwt jwt = decode();
        requireToken(jwt);
        if (verified != null && (!verified.getSubject().equals(jwt.getSubject())
                || !verified.getId().equals(jwt.getId())
                || !verified.getClaimAsString("sid").equals(jwt.getClaimAsString("sid"))
                || !verified.getHeaders().get("kid").equals(jwt.getHeaders().get("kid")))) throw unavailable();
        long version = organization(false);
        var currentIdentity = identities.read(jwt, false);
        Grant currentGrant = grant(jwt, version);
        if (!access.permits(jwt, facts(version), ProductAccessPolicy.Action.CREATE)) throw unavailable();
        remember(version, currentIdentity, currentGrant);
        Jwt last = decode();
        requireToken(last);
        if (!jwt.getClaims().equals(last.getClaims()) || !jwt.getHeaders().equals(last.getHeaders())
                || version != organization(false) || !currentIdentity.equals(identities.read(last, false))
                || !currentGrant.equals(grant(last, version))) throw unavailable();
        watermark.assertUnchanged(generation);
        requireToken(last);
        requireDeadlines(currentIdentity, currentGrant);
        verified = jwt;
        return jwt;
    }

    void requireOwned(Instant at, ProductGrantWatermark.OwnedChange owned) {
        if (verified == null || at == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(db.getDataSource())) throw unavailable();
        owned.requireCurrent(watermark);
        requireToken(verified);
        long version = organization(true);
        var currentIdentity = identities.read(verified, true);
        single(db.query("SELECT authorization_epoch FROM id2_current_epoch WHERE issuer=? AND subject_id=? "
                + "AND organization=? AND domain_code='CONSULTATION' AND resource_type='ORGANIZATION' "
                + "AND resource_id=? AND action_code='CREATE' AND purpose='TASK_WORK' FOR UPDATE",
                (rs, n) -> rs.getLong(1), issuer, verified.getSubject(), organization, organization));
        Grant currentGrant = grant(verified, version);
        if (!access.permitsOwnedCreate(verified, facts(version), owned)) throw unavailable();
        remember(version, currentIdentity, currentGrant);
        owned.requireCurrent(watermark);
        requireToken(verified);
        requireDeadlines(currentIdentity, currentGrant);
    }

    private Jwt decode() {
        try { return decoder.decode(token); }
        catch (org.springframework.security.oauth2.jwt.JwtException failure) {
            throw new IllegalStateException("Current human creation token unavailable", failure);
        }
    }

    private void remember(long version, ProductHumanIdentityLocks.Identity currentIdentity, Grant currentGrant) {
        if (organizationVersion != null && (organizationVersion != version || !identity.equals(currentIdentity)
                || !grant.equals(currentGrant))) throw unavailable();
        organizationVersion = version; identity = currentIdentity; grant = currentGrant;
    }
    private long organization(boolean locking) {
        return single(db.query("SELECT version FROM con3_organization WHERE organization_id=? AND status='ACTIVE'"
                + (locking ? " FOR UPDATE" : ""), (rs, n) -> rs.getLong(1), organization));
    }
    private ProductConsultationAccess.ResourceFacts facts(long version) {
        return new ProductConsultationAccess.ResourceFacts(organization, organization, organization + ":" + version,
                "ORGANIZATION", organization, version);
    }
    private Grant grant(Jwt jwt, long version) {
        return single(db.query("SELECT g.grant_id,c.authorization_epoch,g.grant_version,g.expires_at "
                + "FROM id2_current_epoch c JOIN id2_grant g ON g.grant_id=c.current_grant_id "
                + "AND g.issuer=c.issuer AND g.subject_id=c.subject_id AND g.organization=c.organization "
                + "AND g.domain_code=c.domain_code AND g.resource_type=c.resource_type AND g.resource_id=c.resource_id "
                + "AND g.action_code=c.action_code AND g.purpose=c.purpose AND g.grant_generation=c.authorization_epoch "
                + "WHERE c.issuer=? AND c.subject_id=? AND c.organization=? AND c.domain_code='CONSULTATION' "
                + "AND c.resource_type='ORGANIZATION' AND c.resource_id=? AND c.action_code='CREATE' "
                + "AND c.purpose='TASK_WORK' AND g.task_scope=? AND g.data_scope=? AND g.resource_version=? "
                + "AND g.status='ACTIVE'", (rs, n) -> new Grant(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getTimestamp(4).toInstant()), issuer, jwt.getSubject(), organization, organization,
                organization, organization + ":" + version, version));
    }
    private void requireToken(Jwt jwt) {
        Instant now = clock.instant();
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getId() == null
                || jwt.getClaimAsString("sid") == null || !(jwt.getHeaders().get("kid") instanceof String)
                || jwt.getExpiresAt() == null || !now.isBefore(jwt.getExpiresAt())
                || jwt.getNotBefore() != null && now.isBefore(jwt.getNotBefore()) || !now.isBefore(expiry)) throw unavailable();
    }
    private void requireDeadlines(ProductHumanIdentityLocks.Identity identity, Grant grant) {
        Instant now = clock.instant();
        if (!now.isBefore(identity.session().expires()) || !now.isBefore(grant.until()) || expiry.isAfter(grant.until())
                || "VERIFY_ONLY".equals(identity.keyStatus()) && (identity.verifyUntil() == null
                    || !now.isBefore(identity.verifyUntil()))) throw unavailable();
    }
    private static <T> T single(List<T> rows) {
        if (rows.size() != 1) throw unavailable();
        return rows.getFirst();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("Current human creation authority required"); }
}
