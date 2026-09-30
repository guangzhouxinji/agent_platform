package cn.xinji.agentplatform.enforcement.material;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Internal job identity has only the exact cleanup grant; no HTTP route can invoke it. */
@Component
@ConditionalOnProperty(name = {"enforcement.v2.enabled", "enforcement.v2.cleanup.enabled"}, havingValue = "true")
public final class EnforcementMaterialCleanupJob {
    private final MaterialProductService service;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final String subject;
    private final String organization;
    private final String workId;

    public EnforcementMaterialCleanupJob(MaterialRepository repository, MaterialFiles files, JdbcTemplate db,
            ProductGrantWatermark watermark, @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.v2.cleanup.subject:}") String subject,
            @Value("${enforcement.v2.cleanup.organization:}") String organization,
            @Value("${enforcement.v2.cleanup.work-id:}") String workId) {
        if (issuer.isBlank() || subject.isBlank() || organization.isBlank() || workId.isBlank())
            throw new IllegalArgumentException("Named enforcement cleanup identity and work scope required");
        this.service = new MaterialProductService(repository, files, null, java.time.Clock.systemUTC());
        this.db = db;
        this.watermark = watermark;
        this.issuer = issuer;
        this.subject = subject;
        this.organization = organization;
        this.workId = workId;
    }

    @Scheduled(fixedDelayString = "${enforcement.v2.cleanup.scan-delay-ms:60000}")
    public void run() {
        requireCurrentJobGrant();
        service.cleanOrphans(this::requireCurrentJobGrant);
    }

    private void requireCurrentJobGrant() {
        long generation = watermark.currentGeneration();
        Integer count = db.queryForObject("SELECT COUNT(*) FROM id2_grant g "
                        + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "JOIN id2_subject u ON u.issuer=g.issuer AND u.subject_id=g.subject_id "
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? "
                        + "AND g.domain_code='ENFORCEMENT' AND g.resource_type='MATERIAL_WORK' "
                        + "AND g.resource_id=? AND g.resource_version=1 AND g.action_code='CLEANUP' "
                        + "AND g.purpose='RETENTION_CLEANUP' AND g.status='ACTIVE' "
                        + "AND u.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP",
                Integer.class, issuer, subject, organization, workId);
        watermark.assertUnchanged(generation);
        if (count == null || count != 1) throw new SecurityException("Current cleanup grant required");
    }
}
