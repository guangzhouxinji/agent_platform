package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

@RestController
@RequestMapping("/v2/enforcement/conduct-rules")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementConductRuleController {
    private final ConductStore store;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final String approvedBasis;

    public EnforcementConductRuleController(ConductStore store, JdbcTemplate db,
                                            ProductGrantWatermark watermark,
                                            @Value("${product.identity.issuer:}") String issuer,
                                            @Value("${enforcement.v2.conduct.approved-basis:}") String approvedBasis) {
        this.store = store;
        this.db = db;
        this.watermark = watermark;
        this.issuer = issuer;
        this.approvedBasis = approvedBasis;
    }

    @PostMapping
    public Receipt draft(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                         @RequestBody ConductRuleService.Draft input) {
        return service(jwt).draft(actor(jwt), key, input);
    }

    @GetMapping("/{id}/versions/{version}")
    public Rule read(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable long version) {
        return service(jwt).read(actor(jwt), id, version);
    }

    @PostMapping("/{id}/versions/{version}/trials")
    public Trial trial(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                       @PathVariable long version, @RequestBody Input input) {
        return service(jwt).trial(actor(jwt), id, version, input);
    }

    @PostMapping("/{id}/versions/{version}/publish")
    public Receipt publish(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @PathVariable long version,
                           @RequestBody ConductRuleService.Publication review) {
        return service(jwt).publish(actor(jwt), key, id, version, review);
    }

    @PostMapping("/{id}/versions/{version}/disable")
    public Receipt disable(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @PathVariable long version) {
        return service(jwt).disable(actor(jwt), key, id, version);
    }

    private ConductRuleService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        return new ConductRuleService(store,
                (actor, org, id, version, action) -> access.require(actor, org, "CONDUCT_RULE",
                        id, version, action, "RULE_MANAGEMENT"),
                (basis, behavior) -> {
                    // Explicit exact-scope acceptance configuration, never inferred from draft fields.
                    if (approvedBasis.isBlank() || basis == null || !approvedBasis.equals(String.join("|",
                            behavior.name(), basis.ref(), basis.version(), basis.locator(),
                            basis.scope(), basis.jurisdiction(), basis.procedure())))
                        throw new SecurityException("Conduct publication basis not approved for scope");
                });
    }

    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jwt.getSubject();
    }

    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT)
    void conflict() { }
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
