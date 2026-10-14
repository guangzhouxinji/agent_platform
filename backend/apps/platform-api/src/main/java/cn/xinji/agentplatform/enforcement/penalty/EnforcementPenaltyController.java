package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import cn.xinji.agentplatform.enforcement.material.ProductEnforcementAccess;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import cn.xinji.agentplatform.enforcement.legalreference.*;
import cn.xinji.agentplatform.enforcement.conduct.ConductStore;
import cn.xinji.agentplatform.enforcement.procedure.ProcedureStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8EvidenceOrganizationStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** One final-product API. Authentication, current grants and upstream reads cannot be bypassed by request flags. */
@RestController
@RequestMapping("/v2/enforcement/penalty")
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public final class EnforcementPenaltyController {
    public record Save(long expectedRevision,Rule rule) {}
    public record Change(long expectedRevision) {}
    public record Trial(long revision,Map<String,PenaltyFormula.Amount> amounts) {}
    public record Generate(String id,long expectedRevision,CaseInput input,List<Ref> rules) {}
    public record Adjustment(long expectedRevision,String ruleId,java.math.BigDecimal amount,String reason,String basis) {}
    public record Review(long expectedRevision,boolean accepted,String reason) {}
    private final Dm8PenaltyStore store,current;
    private final JdbcTemplate db;
    private final PlatformTransactionManager manager;
    private final ProductGrantWatermark watermark;
    private final LegalReferenceStore legal;
    private final LegalKnowledgePort source;
    private final LegalReferenceService.Target target;
    private final ConductStore conduct;
    private final ProcedureStore procedure;
    private final ObjectMapper json;
    private final String issuer,caseFile;
    private final cn.xinji.agentplatform.enforcement.material.MaterialRepository materials;
    private final cn.xinji.agentplatform.enforcement.material.MaterialFiles files;
    public EnforcementPenaltyController(@Qualifier("penaltyStore")Dm8PenaltyStore store,@Qualifier("penaltyCurrentStore")Dm8PenaltyStore current,
            JdbcTemplate db,PlatformTransactionManager manager,ProductGrantWatermark watermark,LegalReferenceStore legal,
            LegalKnowledgePort source,LegalReferenceService.Target target,ProcedureStore procedure,ObjectMapper json,
            cn.xinji.agentplatform.enforcement.material.MaterialRepository materials,cn.xinji.agentplatform.enforcement.material.MaterialFiles files,
            @Value("${product.identity.issuer:}")String issuer,@Value("${enforcement.v2.evidence.case-input-file:}")String caseFile) {
        this.store=store;this.current=current;this.db=db;this.manager=manager;this.watermark=watermark;this.legal=legal;
        this.source=source;this.target=target;this.conduct=new cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ConductStore(db,new TransactionTemplate(manager),json,Clock.systemUTC());
        this.procedure=procedure;this.json=json;this.issuer=issuer;this.caseFile=caseFile;
        this.materials=materials;this.files=files;
    }
    @PostMapping("/rules") public Receipt save(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody Save request) {
        return services(jwt).rules().save(actor(jwt),key,request.expectedRevision(),request.rule());
    }
    @GetMapping("/rules/{id}") public Rule rule(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision) {
        return services(jwt).rules().read(actor(jwt),id,revision);
    }
    @GetMapping("/rules/{id}/history") public List<Rule> ruleHistory(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {return services(jwt).rules().history(actor(jwt),id);}
    @GetMapping("/rules") public List<Rule> rules(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization,
            @RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="20")int limit) {
        var service=services(jwt);return coordinates(jwt,organization,"PENALTY_RULE",offset,limit).stream().map(c->service.rules().read(actor(jwt),c.id(),c.revision())).toList();
    }
    @PostMapping("/rules/{id}/validate") public PenaltyPublishChecks.Result validate(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Change request) {
        return services(jwt).rules().validate(actor(jwt),id,request.expectedRevision());
    }
    @PostMapping("/rules/{id}/trial") public PenaltyFormula.Result trial(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestBody Trial request) {
        return services(jwt).rules().trial(actor(jwt),id,request.revision(),request.amounts());
    }
    @PostMapping("/rules/{id}/publish") public Receipt publish(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Change request) {
        return services(jwt).rules().publish(actor(jwt),key,id,request.expectedRevision());
    }
    @PostMapping("/rules/{id}/disable") public Receipt disable(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Change request) {
        return services(jwt).rules().disable(actor(jwt),key,id,request.expectedRevision());
    }
    @PostMapping("/suggestions") public Receipt generate(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@RequestBody Generate request) {
        return services(jwt).suggestions().generate(actor(jwt),key,request.id(),request.expectedRevision(),request.input(),request.rules());
    }
    @GetMapping("/suggestions/{id}") public Suggestion suggestion(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@RequestParam long revision) {
        return services(jwt).suggestions().read(actor(jwt),id,revision);
    }
    @GetMapping("/suggestions/{id}/history") public List<Suggestion> suggestionHistory(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {return services(jwt).suggestions().history(actor(jwt),id);}
    @GetMapping("/suggestions") public List<Suggestion> suggestions(@AuthenticationPrincipal Jwt jwt,@RequestParam String organization,
            @RequestParam(defaultValue="0")int offset,@RequestParam(defaultValue="20")int limit) {
        var service=services(jwt);return coordinates(jwt,organization,"PENALTY_SUGGESTION",offset,limit).stream().map(c->service.suggestions().read(actor(jwt),c.id(),c.revision())).toList();
    }
    @PostMapping("/suggestions/{id}/adjust") public Receipt adjust(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Adjustment request) {
        return services(jwt).suggestions().adjust(actor(jwt),key,id,request.expectedRevision(),request.ruleId(),request.amount(),request.reason(),request.basis());
    }
    @PostMapping("/suggestions/{id}/review") public Receipt review(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")String key,@PathVariable String id,@RequestBody Review request) {
        return services(jwt).suggestions().review(actor(jwt),key,id,request.expectedRevision(),request.accepted(),request.reason());
    }
    private Services services(Jwt jwt) {
        actor(jwt);var access=new ProductEnforcementAccess(db,watermark.independentDatabase(),watermark,jwt,issuer,Clock.systemUTC());
        var inputs=new PenaltyProductInputs(access,legal,source,target,conduct,procedure,
            new Dm8EvidenceOrganizationStore(db,new TransactionTemplate(manager),json,Clock.systemUTC()),json,caseFile.isBlank()?null:Path.of(caseFile),current,materials,files);
        Access grants=(a,o,k,id,r,action)->access.require(a,o,k,id,r,action,"PENALTY_ANALYSIS");
        return new Services(new PenaltyRuleService(store,grants,inputs),new PenaltySuggestionService(store,grants,inputs,Clock.systemUTC()));
    }
    private static String actor(Jwt jwt){if(jwt==null||jwt.getSubject()==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);return jwt.getSubject();}
    private List<Coordinate> coordinates(Jwt jwt,String org,String kind,int offset,int limit) {
        if(offset<0||limit<1||limit>100)throw new IllegalArgumentException("Bounded pagination required");
        return db.query("SELECT r.resource_id,r.revision FROM enf11_penalty_revision r WHERE r.organization_id=? AND r.resource_kind=? "
            +"AND r.revision=(SELECT MAX(x.revision) FROM enf11_penalty_revision x WHERE x.resource_kind=r.resource_kind AND x.resource_id=r.resource_id) "
            +"AND EXISTS (SELECT 1 FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.resource_id=r.resource_id AND g.resource_version=r.revision "
            +"AND g.organization=r.organization_id AND g.subject_id=? AND g.issuer=? AND g.domain_code='ENFORCEMENT' AND g.resource_type=? AND g.action_code='READ' "
            +"AND g.purpose='PENALTY_ANALYSIS' AND g.status='ACTIVE' AND g.expires_at>CURRENT_TIMESTAMP) ORDER BY r.created_at DESC,r.resource_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (r,n)->new Coordinate(r.getString(1),r.getLong(2)),org,kind,actor(jwt),issuer,kind,offset,limit);
    }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN) void forbidden() {}
    @ExceptionHandler(PenaltyPublishRejected.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,Object> publishRejected(PenaltyPublishRejected error,jakarta.servlet.http.HttpServletRequest request) {
        return Map.of("code","PENALTY_PUBLICATION_REJECTED","message","规则发布条件未通过，请处理核验缺项","gaps",error.gaps(),"request_id",requestId(request));
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,Object> conflict(IllegalArgumentException error,jakarta.servlet.http.HttpServletRequest request) {
        String message=error.getMessage()==null?"Input rejected":error.getMessage();
        boolean version=message.toLowerCase(java.util.Locale.ROOT).contains("version conflict")||message.contains("Idempotency conflict");
        return Map.of("code",version?"PENALTY_VERSION_CONFLICT":"PENALTY_INPUT_REJECTED","message",message,"request_id",requestId(request));
    }
    @ExceptionHandler({IllegalStateException.class,org.springframework.dao.DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String,Object> unavailable(jakarta.servlet.http.HttpServletRequest request) {
        return Map.of("code","PENALTY_DEPENDENCY_UNAVAILABLE","message","来源、原件或现时水位不可用；未返回成功结果","request_id",requestId(request));
    }
    private static String requestId(jakarta.servlet.http.HttpServletRequest request) {
        String key=request.getHeader("Idempotency-Key");return key!=null&&key.matches("[A-Za-z0-9_-]{1,120}")?key:java.util.UUID.randomUUID().toString();
    }
    private record Services(PenaltyRuleService rules,PenaltySuggestionService suggestions) {}
    private record Coordinate(String id,long revision) {}
}
