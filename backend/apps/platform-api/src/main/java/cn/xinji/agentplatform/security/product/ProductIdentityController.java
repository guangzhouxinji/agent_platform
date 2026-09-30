package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;

@RestController
@RequestMapping("/v2/identity")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductIdentityController {
    public record RegisterSubject(String loginName) { }
    public record ActivateSubject(String activation, String password) { }
    public record IssueGrant(ProductGrants.Scope scope, Instant expiresAt, long expectedEpoch) { }
    public record RevokeGrant(long expectedVersion) { }
    public record RevokeMaintainer(long expectedVersion) { }
    public record ScopeView(String grantId, String organization, String domain, String taskScope, String dataScope,
                            String resourceType, String resourceId, long resourceVersion, String action,
                            String purpose, Instant expiresAt, long grantVersion, long authorizationEpoch) { }
    public record Self(String issuer, String subject, String sessionId, List<ScopeView> grants) { }

    private final ProductMaintenanceProof maintenance;
    private final ProductSubjects subjects;
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final ProductSigningKeys keys;
    private final ProductInitialTaskService initialTasks;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;

    public ProductIdentityController(ProductMaintenanceProof maintenance, ProductSubjects subjects,
            ProductGrants grants, ProductGrantWatermark watermark, ProductSigningKeys keys,
            ProductInitialTaskService initialTasks,
            JdbcTemplate db, ObjectMapper mapper) {
        this.maintenance = maintenance;
        this.subjects = subjects;
        this.grants = grants;
        this.watermark = watermark;
        this.keys = keys;
        this.initialTasks = initialTasks;
        this.db = db;
        this.mapper = mapper;
    }

    @PostMapping("/subjects")
    public ProductSubjects.Registration register(@RequestBody RegisterSubject body, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS);
        return subjects.register(body.loginName(), actor);
    }

    @PostMapping("/subjects/{id}/activate")
    public void activate(@PathVariable String id, @RequestBody ActivateSubject body, HttpServletRequest request) {
        if (!request.isSecure()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        subjects.activate(id, body.activation(), body.password());
    }

    @PostMapping("/subjects/{id}/disable")
    public void disable(@PathVariable String id, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS);
        watermark.change(() -> {
            subjects.disable(id, actor);
            return null;
        });
    }

    @PostMapping("/grants")
    public ProductGrants.Receipt issue(@RequestBody IssueGrant body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.GRANTS);
        return watermark.change(() -> grants.issue(body.scope(), body.expiresAt(), body.expectedEpoch(), actor,
                requestId, digest(List.of(actor, body))));
    }

    @PostMapping("/organizations/initial-task")
    public ProductInitialTaskService.Result initialTask(@RequestBody ProductInitialTaskService.Input body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS);
        String grantActor = maintenance.require(request, ProductMaintenanceProof.Role.GRANTS);
        if (!actor.equals(grantActor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String organization = maintenance.requireOrganization(request, ProductMaintenanceProof.Role.GRANTS);
        return initialTasks.create(body, requestId, actor, organization);
    }

    @PostMapping("/grants/{id}/revoke")
    public ProductGrants.Receipt revoke(@PathVariable String id, @RequestBody RevokeGrant body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.GRANTS);
        return watermark.change(() -> grants.revoke(id, body.expectedVersion(), actor,
                requestId, digest(List.of(actor, id, body))));
    }

    @PostMapping("/keys/rotate")
    public String rotate(HttpServletRequest request) {
        return keys.rotate(maintenance.require(request, ProductMaintenanceProof.Role.KEYS));
    }

    @PostMapping("/maintenance/revoke")
    public void revokeMaintainer(@RequestBody RevokeMaintainer body, HttpServletRequest request) {
        maintenance.revoke(request, body.expectedVersion());
    }

    @GetMapping("/me")
    public Self me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long authorizationGeneration = watermark.currentGeneration();
        List<ScopeView> current = db.query("SELECT g.grant_id,g.organization,g.domain_code,g.task_scope,g.data_scope,"
                        + "g.resource_type,g.resource_id,g.resource_version,g.action_code,g.purpose,"
                        + "g.expires_at,g.grant_version,c.authorization_epoch FROM id2_grant g "
                        + "JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
                        + "WHERE g.issuer=? AND g.subject_id=? AND g.status='ACTIVE' "
                        + "AND g.expires_at>CURRENT_TIMESTAMP", (rs, row) -> new ScopeView(rs.getString(1),
                        rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10),
                        rs.getTimestamp(11).toInstant(), rs.getLong(12), rs.getLong(13)),
                jwt.getIssuer().toString(), jwt.getSubject());
        watermark.assertUnchanged(authorizationGeneration);
        return new Self(jwt.getIssuer().toString(), jwt.getSubject(), jwt.getClaimAsString("sid"), current);
    }

    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        db.update("UPDATE id2_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP "
                        + "WHERE issuer=? AND subject_id=? AND sid=? AND jti=? AND status='ACTIVE'",
                jwt.getIssuer().toString(), jwt.getSubject(), jwt.getClaimAsString("sid"), jwt.getId());
    }

    private String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(value)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("Maintenance request cannot be digested", failure);
        }
    }

    @ExceptionHandler(ProductSubjects.InvalidActivationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    void invalidActivation() { }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidRequest() { }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void conflictingState() { }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableState() { }
}
