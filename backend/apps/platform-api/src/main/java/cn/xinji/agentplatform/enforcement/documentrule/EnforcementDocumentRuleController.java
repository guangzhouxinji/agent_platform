package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateStore;
import cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateFiles;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/enforcement/document-rules")
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public final class EnforcementDocumentRuleController {
    public record Save(long expectedRevision,Rule rule) {}
    public record Change(long expectedRevision,String purpose) {}
    public record ValidationResult(boolean valid,boolean reviewRequired,List<Diagnostic> diagnostics,long ruleRevision) {}
    public record TrialResult(boolean complete,boolean reviewRequired,List<Mapped> fields,List<Diagnostic> diagnostics,long ruleRevision) {}
    private final Dm8DocumentRuleStore store;
    private final DocumentTemplateStore templates;
    private final DocumentTemplateFiles files;
    private final JdbcTemplate db;
    private final ProductGrantWatermark watermark;
    private final ObjectMapper json;
    private final String issuer;
    private final Path catalog;
    private final DocumentRuleDependencyHeads heads;
    public EnforcementDocumentRuleController(Dm8DocumentRuleStore store,DocumentTemplateStore templates,
            DocumentTemplateFiles files,JdbcTemplate db,ProductGrantWatermark watermark,ObjectMapper json,DocumentRuleDependencyHeads heads,
            @Value("${product.identity.issuer:}")String issuer,
            @Value("${enforcement.v2.document-rule.catalog-file:}")String catalog) {
        this.store=store;this.templates=templates;this.files=files;this.db=db;this.watermark=watermark;
        this.json=json;this.issuer=issuer;this.catalog=catalog.isBlank()?null:Path.of(catalog);
        this.heads=heads;
    }
    @PostMapping public DocumentRuleService.Receipt save(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody Save input) {
        return service(jwt).save(actor(jwt),key,input.expectedRevision(),input.rule());
    }
    @GetMapping public List<Rule> list(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization) {
        var service=service(jwt);
        var ids=db.query("SELECT DISTINCT r.rule_id,r.revision FROM enf10_document_rule r JOIN id2_grant g ON g.resource_id=r.rule_id "
            + "AND g.resource_version=r.revision JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id "
            + "WHERE r.organization_id=? AND g.organization=? AND g.subject_id=? AND g.issuer=? AND g.domain_code='ENFORCEMENT' "
            + "AND g.resource_type='DOCUMENT_RULE' AND g.action_code='READ' AND g.purpose='DOCUMENT_MAPPING' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP "
            + "AND r.revision=(SELECT MAX(x.revision) FROM enf10_document_rule x WHERE x.rule_id=r.rule_id) ORDER BY r.rule_id",
            (rs,n)->new Coordinate(rs.getString(1),rs.getLong(2)),organization,organization,actor(jwt),issuer);
        return ids.stream().map(c->service.read(actor(jwt),c.id(),c.revision())).toList();
    }
    @GetMapping("/{id}") public Rule read(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision) {return service(jwt).read(actor(jwt),id,revision);}
    @GetMapping("/{id}/history") public List<Rule> history(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {return service(jwt).history(actor(jwt),id);}
    @GetMapping("/{id}/attempts") public List<Attempt> attempts(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {return service(jwt).attempts(actor(jwt),id);}
    @PostMapping("/{id}/validate") public ValidationResult validate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Change input) {
        var v=service(jwt).validate(actor(jwt),id,input.expectedRevision(),purpose(input));
        return new ValidationResult(v.valid(),v.reviewRequired(),v.diagnostics(),store.latest(id).orElseThrow().revision());
    }
    @PostMapping("/{id}/trial") public TrialResult trial(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Change input) {
        var v=service(jwt).trial(actor(jwt),id,input.expectedRevision(),purpose(input));
        return new TrialResult(v.complete(),v.reviewRequired(),v.fields(),v.diagnostics(),store.latest(id).orElseThrow().revision());
    }
    @PostMapping("/{id}/publish") public DocumentRuleService.Receipt publish(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Change input) {return service(jwt).publish(actor(jwt),key,id,input.expectedRevision(),purpose(input));}
    @PostMapping("/{id}/disable") public DocumentRuleService.Receipt disable(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Change input) {return service(jwt).disable(actor(jwt),key,id,input.expectedRevision());}
    private DocumentRuleService service(Jwt jwt) {
        actor(jwt);
        if (jwt.getIssuer()==null || !issuer.equals(jwt.getIssuer().toString()) || jwt.getExpiresAt()==null
                || !jwt.getExpiresAt().isAfter(java.time.Instant.now())) throw new SecurityException("Current identity required");
        long generation=watermark.currentGeneration();
        Integer session=db.queryForObject("SELECT COUNT(*) FROM id2_session s JOIN id2_subject u ON u.issuer=s.issuer AND u.subject_id=s.subject_id "
            + "WHERE s.issuer=? AND s.subject_id=? AND s.sid=? AND s.jti=? AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'",
            Integer.class,issuer,actor(jwt),jwt.getClaimAsString("sid"),jwt.getId());
        watermark.assertUnchanged(generation);
        if(session==null||session!=1)throw new SecurityException("Current identity session required");
        var access=new ProductEnforcementAccess(db,watermark.independentDatabase(),watermark,jwt,issuer,Clock.systemUTC());
        return new DocumentRuleService(store,(actor,org,id,revision,action)->access.require(actor,org,"DOCUMENT_RULE",id,revision,action,"DOCUMENT_MAPPING"),
            new FileDocumentRuleInputs(catalog,json,templates,files,access,heads));
    }
    private static String purpose(Change input) {if(!"DOCUMENT_MAPPING".equals(input.purpose()))throw new IllegalArgumentException("Document mapping purpose required");return input.purpose();}
    private static String actor(Jwt jwt) {if(jwt==null||jwt.getSubject()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return jwt.getSubject();}
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() {}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT) void conflict() {}
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE) void unavailable() {}
    private record Coordinate(String id,long revision) {}
}
