package cn.xinji.agentplatform.enforcement.conduct;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceOrganizationService;
import cn.xinji.agentplatform.enforcement.evidence.EvidenceRuleStore;
import cn.xinji.agentplatform.enforcement.evidence.VerifiedEvidenceSource;
import cn.xinji.agentplatform.enforcement.material.MaterialFiles;
import cn.xinji.agentplatform.enforcement.material.MaterialRepository;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
@RequestMapping("/v2/enforcement/conduct-analyses")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementConductAnalysisController {
    private final ConductStore store;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final EvidenceRuleStore rules;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final String caseInput;

    public EnforcementConductAnalysisController(ConductStore store, JdbcTemplate db,
            PlatformTransactionManager manager, ObjectMapper json, EvidenceRuleStore rules,
            MaterialRepository materials, MaterialFiles files, ProductGrantWatermark watermark,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.v2.evidence.case-input-file:}") String caseInput) {
        this.store = store;
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.json = json;
        this.rules = rules;
        this.materials = materials;
        this.files = files;
        this.watermark = watermark;
        this.issuer = issuer;
        this.caseInput = caseInput;
    }

    @PostMapping
    public Receipt analyze(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                           @RequestBody Input input) {
        return service(jwt).analyze(actor(jwt), key, input);
    }

    @GetMapping("/{id}/versions/{version}")
    public Report read(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                       @PathVariable long version) {
        return service(jwt).read(actor(jwt), id, version);
    }

    @PostMapping("/{id}/versions/{version}/reviews")
    public Receipt review(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                          @PathVariable String id, @PathVariable long version,
                          @RequestBody ConductAnalysisService.Review input) {
        if (input == null || input.expectedVersion() != version)
            throw new IllegalArgumentException("Review version mismatch");
        return service(jwt).review(actor(jwt), key, id, input);
    }

    @PostMapping("/{id}/versions/{version}/reruns")
    public Receipt rerun(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                         @PathVariable String id, @PathVariable long version,
                         @RequestBody ConductAnalysisService.Rerun input) {
        if (input == null || input.expectedVersion() != version)
            throw new IllegalArgumentException("Rerun version mismatch");
        return service(jwt).rerun(actor(jwt), key, id, input);
    }

    private ConductAnalysisService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (caseInput.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        var organizationStore = new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC());
        var source = new VerifiedEvidenceSource(Path.of(caseInput), json, materials, files,
                access, Clock.systemUTC());
        var organizations = new EvidenceOrganizationService(rules, organizationStore, source,
                (actor, org, type, id, version, action, purpose) ->
                        access.require(actor, org, type, id, version, action, purpose));
        return new ConductAnalysisService(store,
                (actor, org, type, id, version, action, purpose) ->
                        access.require(actor, org, type, id, version, action, purpose),
                new LockedConductEvidence(organizations, materials, files, access, json, caseInput),
                new CaseFileConductScope(Path.of(caseInput), json));
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
