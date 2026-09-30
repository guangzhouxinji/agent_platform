package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance-only workload registration; certificates never authorize themselves. */
@RestController
@RequestMapping("/v2/identity/workloads")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductWorkloadController {
    public record Register(String certificateSha256, ProductWorkloadSubjects.Kind kind, Instant expiresAt) { }
    public record Revoke(long expectedVersion) { }
    public record GrantTask(String taskId, Instant expiresAt, long expectedEpoch) { }

    private final ProductMaintenanceProof maintenance;
    private final JdbcTemplate db;
    private final PlatformTransactionManager manager;
    private final ProductGrantWatermark watermark;
    private final ProductGrants grants;
    private final String issuer;

    public ProductWorkloadController(ProductMaintenanceProof maintenance, JdbcTemplate db,
            PlatformTransactionManager manager, ProductGrantWatermark watermark, ProductGrants grants,
            @org.springframework.beans.factory.annotation.Value("${product.identity.issuer:}") String issuer) {
        this.maintenance = maintenance;
        this.db = db;
        this.manager = manager;
        this.watermark = watermark;
        this.grants = grants;
        this.issuer = issuer;
    }

    @PostMapping
    public ProductWorkloadSubjects.Registration register(@RequestBody Register body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintainer(request);
        return subjects().register(body.certificateSha256(),
                maintenance.requireOrganization(request, ProductMaintenanceProof.Role.GRANTS),
                body.kind(), body.expiresAt(), requestId, actor);
    }

    @PostMapping("/{subject}/revoke")
    public long revoke(@PathVariable String subject, @RequestBody Revoke body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintainer(request);
        return subjects().revoke(subject,
                maintenance.requireOrganization(request, ProductMaintenanceProof.Role.GRANTS),
                body.expectedVersion(), requestId, actor);
    }

    @PostMapping("/{subject}/task-grants")
    public ProductGrants.Receipt grantTask(@PathVariable String subject, @RequestBody GrantTask body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintainer(request);
        String organization = maintenance.requireOrganization(request, ProductMaintenanceProof.Role.GRANTS);
        String digest = sha(actor, subject, organization, body.taskId(),
                String.valueOf(body.expiresAt()), Long.toString(body.expectedEpoch()));
        return watermark.change(() -> grants.issueWorkload(issuer, subject, organization,
                body.taskId(), body.expiresAt(), body.expectedEpoch(), actor, requestId, digest));
    }

    private static String sha(String... fields) {
        try {
            var hash = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : fields)
                hash.update((field.length() + ":" + field)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String maintainer(HttpServletRequest request) {
        String subjects = maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS);
        String grants = maintenance.require(request, ProductMaintenanceProof.Role.GRANTS);
        if (!subjects.equals(grants)) throw new IllegalStateException("Maintenance identity changed");
        return subjects;
    }

    private ProductWorkloadSubjects subjects() {
        return new ProductWorkloadSubjects(db, new TransactionTemplate(manager), watermark,
                Clock.systemUTC(), issuer);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
