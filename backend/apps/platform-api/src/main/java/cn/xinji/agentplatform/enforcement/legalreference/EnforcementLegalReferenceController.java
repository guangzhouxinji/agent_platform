package cn.xinji.agentplatform.enforcement.legalreference;

import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import java.time.Clock;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static cn.xinji.agentplatform.enforcement.legalreference.LegalReferenceContract.*;

@RestController
@RequestMapping("/v2/enforcement")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementLegalReferenceController {
    private final LegalReferenceStore store;
    private final LegalKnowledgePort source;
    private final LegalReferenceService.Target target;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final String issuer;

    public EnforcementLegalReferenceController(LegalReferenceStore store, LegalKnowledgePort source,
            LegalReferenceService.Target target, JdbcTemplate db, ProductGrantWatermark watermark,
            @org.springframework.beans.factory.annotation.Value("${product.identity.issuer:}") String issuer) {
        this.store = store; this.source = source; this.target = target;
        this.db = db; this.watermark = watermark; this.issuer = issuer;
    }

    @PostMapping("/legal-basis/searches")
    public Receipt search(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @RequestBody SearchRequest input) {
        return service(jwt).search(actor(jwt), key, input);
    }

    @GetMapping("/legal-basis/searches/{id}")
    public SearchResult search(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                               @RequestParam(defaultValue = "1") long revision) {
        return service(jwt).readSearch(actor(jwt), id, revision);
    }

    @PostMapping("/legal-references")
    public Receipt bind(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                        @RequestBody Selection input) {
        return service(jwt).bind(actor(jwt), key, input);
    }

    @GetMapping("/legal-references/{id}")
    public ReferenceRevision reference(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                       @RequestParam long revision) {
        return service(jwt).read(actor(jwt), id, revision);
    }

    @GetMapping("/legal-references/{id}/history")
    public List<ReferenceRevision> history(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).history(actor(jwt), id);
    }

    @PostMapping("/legal-references/{id}/reviews")
    public Receipt review(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @RequestBody LegalReferenceService.Review input) {
        return service(jwt).review(actor(jwt), key, id, input);
    }

    @PostMapping("/legal-references/{id}/unbind")
    public Receipt unbind(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @RequestBody LegalReferenceService.Change input) {
        return service(jwt).unbind(actor(jwt), key, id, input);
    }

    @PostMapping("/legal-references/{id}/rechecks")
    public Receipt recheck(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @PathVariable String id, @RequestBody LegalReferenceService.Change input) {
        return service(jwt).recheck(actor(jwt), key, id, input);
    }

    private LegalReferenceService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark,
                jwt, issuer, Clock.systemUTC());
        return new LegalReferenceService(store, source, access::require, (actor, org, type, id,
                version, anchor, purpose) -> {
            access.require(actor, org, type, id, version, "READ", purpose);
            return target.require(actor, org, type, id, version, anchor, purpose);
        });
    }

    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jwt.getSubject();
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() { }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT) void conflict() { }
    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() { }
}
