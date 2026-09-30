package cn.xinji.agentplatform.enforcement.handoff;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8HandoffStore;
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
import java.util.List;
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

@RestController
@RequestMapping("/v2/enforcement")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementHandoffController {
    private final HandoffStore store;
    private final EvidenceRuleStore rules;
    private final MaterialRepository materials;
    private final MaterialFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String issuer;
    private final String caseInput;

    public EnforcementHandoffController(JdbcTemplate db, PlatformTransactionManager manager,
            ObjectMapper json, EvidenceRuleStore rules, MaterialRepository materials,
            MaterialFiles files, ProductGrantWatermark watermark,
            @Value("${product.identity.issuer:}") String issuer,
            @Value("${enforcement.v2.evidence.case-input-file:}") String caseInput) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.json = json;
        this.rules = rules;
        this.materials = materials;
        this.files = files;
        this.watermark = watermark;
        this.issuer = issuer;
        this.caseInput = caseInput;
        this.store = new Dm8HandoffStore(db, tx, json, Clock.systemUTC());
    }

    @PostMapping("/work-packages")
    public HandoffStore.Receipt create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody HandoffService.PackageInput input) {
        return service(jwt).create(actor(jwt), key, input);
    }

    @GetMapping("/work-packages/{id}/versions/{version}")
    public HandoffStore.Package readPackage(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String id, @PathVariable long version) {
        return service(jwt).readPackage(actor(jwt), id, version);
    }

    @PostMapping("/work-packages/{id}/revisions")
    public HandoffStore.Receipt revise(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody HandoffService.RevisionInput input) {
        return service(jwt).revise(actor(jwt), key, id, input);
    }

    @PostMapping("/work-packages/{packageId}/versions/{version}/human-requests")
    public HandoffStore.Receipt request(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @PathVariable String packageId, @PathVariable long version,
            @RequestBody HandoffService.RequestInput input) {
        if (input == null || !packageId.equals(input.packageId()) || version != input.packageVersion())
            throw new IllegalArgumentException("Request package reference mismatch");
        return service(jwt).request(actor(jwt), key, input);
    }

    @GetMapping("/human-requests/{id}")
    public HandoffStore.Request readRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readRequest(actor(jwt), id);
    }

    @GetMapping("/human-tasks")
    public List<HandoffStore.Request> tasks(@AuthenticationPrincipal Jwt jwt) {
        return service(jwt).tasks(actor(jwt));
    }

    @PostMapping("/human-requests/{id}/claims")
    public HandoffStore.Receipt claim(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody HandoffService.ClaimInput input) {
        return service(jwt).claim(actor(jwt), key, id, input);
    }

    @PostMapping("/human-requests/{id}/results")
    public HandoffStore.Receipt result(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody HandoffService.ResultInput input) {
        return service(jwt).submit(actor(jwt), key, id, input);
    }

    @GetMapping("/human-requests/{id}/result")
    public HandoffStore.Result readResult(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readResult(actor(jwt), id);
    }

    @PostMapping("/human-requests/{id}/returns")
    public HandoffStore.Receipt returnTask(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody HandoffService.ReturnInput input) {
        return service(jwt).returnTask(actor(jwt), key, id, input);
    }

    @PostMapping("/human-requests/{id}/withdrawals")
    public HandoffStore.Receipt withdraw(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody HandoffService.WithdrawalInput input) {
        return service(jwt).withdraw(actor(jwt), key, id, input);
    }

    private HandoffService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (caseInput.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        var access = new ProductEnforcementAccess(db, watermark.independentDatabase(),
                watermark, jwt, issuer, Clock.systemUTC());
        var evidence = new EvidenceOrganizationService(rules,
                new Dm8EvidenceOrganizationStore(db, tx, json, Clock.systemUTC()),
                new VerifiedEvidenceSource(Path.of(caseInput), json, materials, files, access,
                        Clock.systemUTC()),
                (actor, org, type, id, version, action, purpose) ->
                        access.require(actor, org, type, id, version, action, purpose));
        return new HandoffService(store, evidence::read,
                (actor, org, type, id, version, action, purpose) ->
                        access.require(actor, org, type, id, version, action, purpose));
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
