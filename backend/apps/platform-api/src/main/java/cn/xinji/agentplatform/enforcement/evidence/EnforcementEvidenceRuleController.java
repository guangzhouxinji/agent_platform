package cn.xinji.agentplatform.enforcement.evidence;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@RestController
@RequestMapping("/v2/enforcement/evidence-rules")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementEvidenceRuleController {
    private final EvidenceRuleStore store;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final String approvedBasis;
    private final EvidenceTrialSource trialSource;

    public EnforcementEvidenceRuleController(EvidenceRuleStore store, JdbcTemplate db,
                                             ProductGrantWatermark watermark,
                                             ObjectMapper json,
                                             @Value("${product.identity.issuer:}") String issuer,
                                             @Value("${enforcement.v2.evidence.approved-basis:}") String approvedBasis,
                                             @Value("${enforcement.v2.evidence.trial-input-root:}") String trialRoot) {
        this.store = store;
        this.db = db;
        this.watermark = watermark;
        this.issuer = issuer;
        this.approvedBasis = approvedBasis;
        this.trialSource = trialRoot.isBlank() ? null : new FileEvidenceTrialSource(Path.of(trialRoot), json);
    }

    @PostMapping
    public EvidenceRuleStore.Receipt draft(@AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader("Idempotency-Key") String key,
                                           @RequestBody EvidenceRuleService.DraftInput input) {
        return service(jwt).draft(actor(jwt), key, input);
    }

    @GetMapping("/{id}/versions/{revision}")
    public EvidenceRuleStore.Rule read(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String id, @PathVariable long revision) {
        return service(jwt).read(actor(jwt), id, revision);
    }

    @GetMapping("/{id}/versions/{revision}/publication")
    public EvidenceRuleStore.PublicationReview publication(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable String id,
                                                           @PathVariable long revision) {
        return service(jwt).publication(actor(jwt), id, revision);
    }

    @PostMapping("/{id}/versions/{revision}/trials")
    public EvidenceRuleTrial.Result trial(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable String id, @PathVariable long revision,
                                          @RequestBody EvidenceRuleTrial.Request request) {
        if (trialSource == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        return new EvidenceRuleTrial(store, trialSource,
                (actor, org, type, resource, version, action, purpose) ->
                        access.require(actor, org, type, resource, version, action, purpose))
                .run(actor(jwt), id, revision, request);
    }

    @PostMapping("/{id}/publications")
    public EvidenceRuleStore.Receipt publish(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @PathVariable String id,
                                             @RequestBody EvidenceRuleService.Publication review) {
        return service(jwt).publish(actor(jwt), key, id, review);
    }

    @PostMapping("/{id}/versions/{revision}/disable")
    public EvidenceRuleStore.Receipt disable(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @PathVariable String id, @PathVariable long revision) {
        return service(jwt).disable(actor(jwt), key, id, revision);
    }

    private EvidenceRuleService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        return new EvidenceRuleService(store,
                (actor, organization, ruleId, version, action) ->
                        access.require(actor, organization, "EVIDENCE_RULE", ruleId, version,
                                action, "RULE_MANAGEMENT"),
                (caseType, basisRef, basisVersion, scope) -> {
                    if (!String.join("|", caseType, basisRef, basisVersion, scope).equals(approvedBasis))
                        throw new SecurityException("Publication basis not approved for scope");
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
