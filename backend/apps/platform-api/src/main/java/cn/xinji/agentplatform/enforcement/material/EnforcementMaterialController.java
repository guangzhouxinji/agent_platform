package cn.xinji.agentplatform.enforcement.material;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.enforcement.material.MaterialRuleTrial;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@RequestMapping("/v2/enforcement")
@ConditionalOnProperty(name = "enforcement.v2.enabled", havingValue = "true")
public final class EnforcementMaterialController {
    public record RevisionInput(long expectedVersion, String correctedText, String reason) { }
    public record RuleStateInput(long revision, String positiveObservationId, String negativeObservationId) { }
    public record DiscoveryInput(String observationId, String ruleId) { }
    public record CollectionInput(long expectedVersion, String sourceRef, String reason) { }
    public record VerificationInput(long expectedVersion, String reason) { }

    private final MaterialRepository repository;
    private final MaterialFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final MaterialPageSource pageSource;
    private final String issuer;

    public EnforcementMaterialController(MaterialRepository repository, MaterialFiles files, JdbcTemplate db,
                                         ProductGrantWatermark watermark, MaterialPageSource pageSource,
                                         @Value("${product.identity.issuer:}") String issuer) {
        this.repository = repository;
        this.files = files;
        this.db = db;
        this.watermark = watermark;
        this.pageSource = pageSource;
        this.issuer = issuer;
    }

    @PostMapping("/sources")
    public MaterialWork.Receipt source(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                       @RequestBody MaterialProductService.SourceInput input) {
        return service(jwt).registerSource(actor(jwt), key, input);
    }
    @GetMapping("/sources/{id}")
    public MaterialWork.Source source(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readSource(actor(jwt), id);
    }
    @PostMapping("/sources/{id}/configuration")
    public MaterialWork.Receipt configureSource(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String id,
            @RequestBody MaterialProductService.SourceConfigurationInput input) {
        return service(jwt).configureSource(actor(jwt), key, id, input);
    }
    @PostMapping("/sources/{id}/syncs")
    public MaterialSourceSync.Result sync(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return syncService(jwt).onePage(actor(jwt), id);
    }
    @GetMapping("/sources/{id}/sync-attempts/{attemptId}/records")
    public List<MaterialRepository.SyncRecord> syncRecords(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String id, @PathVariable String attemptId) {
        return syncService(jwt).records(actor(jwt), id, attemptId);
    }
    private MaterialSourceSync syncService(Jwt jwt) {
        var access = access(jwt);
        return new MaterialSourceSync(repository, pageSource,
                new MaterialProductService(repository, files, access, Clock.systemUTC()),
                access, Clock.systemUTC());
    }
    @PostMapping("/observations")
    public MaterialWork.Receipt observe(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                        @RequestBody MaterialProductService.ObservationInput input) {
        return service(jwt).observe(actor(jwt), key, input);
    }
    @PostMapping("/discovery-rules")
    public MaterialWork.Receipt draft(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                      @RequestBody MaterialProductService.RuleInput input) {
        return service(jwt).draftRule(actor(jwt), key, input);
    }
    @PostMapping("/discovery-rules/{id}/publish")
    public MaterialWork.Receipt publish(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                        @PathVariable String id, @RequestBody RuleStateInput input) {
        return service(jwt).ruleState(actor(jwt), key, id, input.revision(), "PUBLISH_RULE",
                input.positiveObservationId(), input.negativeObservationId());
    }

    @PostMapping("/discovery-rules/{id}/disable")
    public MaterialWork.Receipt disable(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                        @PathVariable String id, @RequestBody RuleStateInput input) {
        return service(jwt).ruleState(actor(jwt), key, id, input.revision(), "DISABLE_RULE", null, null);
    }

    @GetMapping("/discovery-rules/{id}")
    public MaterialWork.Rule rule(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readRule(actor(jwt), id);
    }

    @GetMapping("/discovery-rules/{id}/trial/{observationId}")
    public MaterialRuleTrial.Result trial(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                                  @PathVariable String observationId) {
        return service(jwt).trialRule(actor(jwt), id, observationId);
    }

    @PostMapping("/discoveries")
    public MaterialWork.Receipt discover(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                         @RequestBody DiscoveryInput input) {
        return service(jwt).discover(actor(jwt), key, input.observationId(), input.ruleId());
    }

    @GetMapping("/clues/{id}")
    public MaterialWork.Clue clue(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readClue(actor(jwt), id);
    }

    @GetMapping("/clues/{id}/rules")
    public List<MaterialWork.RuleRef> clueRules(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readClueRules(actor(jwt), id);
    }

    @GetMapping("/observations/{id}")
    public MaterialWork.Observation observation(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readObservation(actor(jwt), id);
    }

    @GetMapping("/observations/{id}/actors")
    public List<MaterialWork.ActorMention> actors(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readActors(actor(jwt), id);
    }

    @GetMapping("/clues/{id}/revisions")
    public List<MaterialWork.Revision> revisions(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).revisions(actor(jwt), id);
    }

    @PostMapping("/clues/{id}/revisions")
    public MaterialWork.Receipt revise(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                       @PathVariable String id, @RequestBody RevisionInput input) {
        return service(jwt).revise(actor(jwt), key, id, input.expectedVersion(),
                input.correctedText(), input.reason());
    }

    @PostMapping("/materials")
    public MaterialWork.Receipt material(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                         @RequestBody MaterialProductService.MaterialInput input) {
        return service(jwt).registerMaterial(actor(jwt), key, input);
    }

    @GetMapping(value = "/materials/{id}/preview", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).preview(actor(jwt), id);
    }

    @GetMapping("/materials/{id}")
    public MaterialWork.Material material(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return service(jwt).readMaterial(actor(jwt), id);
    }

    @GetMapping("/materials/{id}/actions")
    public List<MaterialWork.MaterialAction> actions(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return collection(jwt).actions(actor(jwt), id);
    }

    @PostMapping("/materials/{id}/collection")
    public MaterialWork.Receipt attest(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                       @PathVariable String id, @RequestBody CollectionInput input) {
        return collection(jwt).attest(actor(jwt), key, id, input.expectedVersion(),
                input.sourceRef(), input.reason());
    }

    @PostMapping("/materials/{id}/verification")
    public MaterialWork.Receipt verify(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                       @PathVariable String id, @RequestBody VerificationInput input) {
        return collection(jwt).verify(actor(jwt), key, id, input.expectedVersion(), input.reason());
    }

    private MaterialCollectionService collection(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new MaterialCollectionService(repository, files, access(jwt), Clock.systemUTC());
    }

    private MaterialProductService service(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new MaterialProductService(repository, files, access(jwt), Clock.systemUTC());
    }

    private ProductEnforcementAccess access(Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return new ProductEnforcementAccess(db, watermark.independentDatabase(), watermark, jwt, issuer,
                Clock.systemUTC());
    }

    private static String actor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
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
