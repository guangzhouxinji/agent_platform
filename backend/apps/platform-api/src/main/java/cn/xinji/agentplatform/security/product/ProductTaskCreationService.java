package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Creates subsequent consultation tasks under a current organization-level grant. */
public final class ProductTaskCreationService {
    public record Input(String organizationId, Instant expiresAt, String retentionRef, long retentionVersion) { }
    public record Result(String taskId, String organizationId, String dataScope, long version, String grantId) { }

    private final JdbcTemplate db;
    private final ProductTaskDirectory directory;
    private final ProductConsultationAccess access;
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final TransactionTemplate transactions;
    private final ProductRetentionPolicy retention;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String issuer;

    public ProductTaskCreationService(JdbcTemplate db, ProductTaskDirectory directory,
            ProductConsultationAccess access, ProductGrants grants, ProductGrantWatermark watermark,
            TransactionTemplate transactions, ProductRetentionPolicy retention, ObjectMapper mapper,
            Clock clock, String issuer) {
        this.db = db;
        this.directory = directory;
        this.access = access;
        this.grants = grants;
        this.watermark = watermark;
        this.transactions = transactions;
        this.retention = retention;
        this.mapper = mapper;
        this.clock = clock;
        this.issuer = issuer;
    }

    public Result create(Jwt jwt, String requestId, Input input) {
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || blank(jwt.getSubject())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (input == null || blank(input.organizationId()) || blank(requestId) || requestId.length() > 200
                || input.expiresAt() == null || !input.expiresAt().isAfter(clock.instant())
                || input.expiresAt().isAfter(clock.instant().plus(retention.maxAge()))
                || !retention.reference().equals(input.retentionRef())
                || retention.version() != input.retentionVersion())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String hash = digest(jwt, requestId, input);
        long generation = watermark.currentGeneration();
        long authorizedVersion = organizationVersion(input.organizationId(), false);
        authorize(jwt, input.organizationId(), authorizedVersion);
        var existing = directory.creationReceipt(requestId);
        if (existing.isPresent()) {
            Result result = replay(existing.get(), hash, jwt);
            watermark.assertUnchanged(generation);
            return result;
        }
        return watermark.change(() -> transactions.execute(status -> {
            long version = organizationVersion(input.organizationId(), true);
            if (version != authorizedVersion) throw new ResponseStatusException(HttpStatus.CONFLICT);
            if (!grantCoversExpiry(jwt, input.organizationId(), version, input.expiresAt()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            var prior = directory.creationReceipt(requestId);
            if (prior.isPresent()) return replay(prior.get(), hash, jwt);
            var creation = new ProductTaskDirectory.Creation(input.organizationId(), issuer, jwt.getSubject(),
                    retention.reference(), retention.version(), retention.evidenceSha256(), input.expiresAt(),
                    requestId, hash);
            var task = directory.insert(creation);
            var scope = new ProductGrants.Scope(issuer, jwt.getSubject(), task.organization(),
                    "CONSULTATION", task.id(), task.dataScope(), "TASK", task.id(),
                    task.authorizationVersion(), "READ", "TASK_WORK");
            var grant = grants.issue(scope, task.expiresAt(), 0, jwt.getSubject(),
                    "task-create-grant:" + requestId, hash);
            directory.recordCreationReceipt(creation, task, grant.grantId());
            return result(task, grant.grantId());
        }));
    }

    private boolean grantCoversExpiry(Jwt jwt, String organization, long version, Instant expiry) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch c "
                + "ON c.current_grant_id=g.grant_id AND c.authorization_epoch=g.grant_generation "
                + "WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.domain_code='CONSULTATION' "
                + "AND g.task_scope=? AND g.data_scope=? AND g.resource_type='ORGANIZATION' "
                + "AND g.resource_id=? AND g.resource_version=? AND g.action_code='CREATE' "
                + "AND g.purpose='TASK_WORK' AND g.status='ACTIVE' AND g.expires_at>=?",
                Integer.class, issuer, jwt.getSubject(), organization, organization,
                organization + ":" + version, organization, version, Timestamp.from(expiry));
        return count != null && count == 1;
    }

    private long organizationVersion(String organization, boolean lock) {
        List<Long> versions = db.query("SELECT version FROM con3_organization WHERE organization_id=? "
                        + "AND status='ACTIVE'" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> rs.getLong(1), organization);
        if (versions.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return versions.getFirst();
    }

    private void authorize(Jwt jwt, String organization, long version) {
        var facts = new ProductConsultationAccess.ResourceFacts(organization, organization,
                organization + ":" + version, "ORGANIZATION", organization, version);
        if (!access.permits(jwt, facts, ProductAccessPolicy.Action.CREATE))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Result replay(ProductTaskDirectory.Receipt receipt, String hash, Jwt jwt) {
        if (!Objects.equals(hash, receipt.requestSha()))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        var task = receipt.task();
        if (!receipt.current() || task == null || !task.expiresAt().isAfter(clock.instant())
                || !issuer.equals(task.ownerIssuer()) || !jwt.getSubject().equals(task.ownerSubject()))
            throw new ResponseStatusException(HttpStatus.GONE);
        return result(task, receipt.grantId());
    }

    private Result result(ProductTaskDirectory.Task task, String grantId) {
        return new Result(task.id(), task.organization(), task.dataScope(), task.version(), grantId);
    }

    private String digest(Jwt jwt, String requestId, Input input) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(List.of(issuer, jwt.getSubject(), requestId,
                    retention, input));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Task creation request cannot be digested", failure);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
