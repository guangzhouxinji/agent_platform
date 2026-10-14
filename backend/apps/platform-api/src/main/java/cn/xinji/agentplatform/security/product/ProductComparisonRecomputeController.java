package cn.xinji.agentplatform.security.product;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** A fresh eligibility check before the existing single-candidate handoff, not another execution engine. */
@RestController
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled","product.consultation.comparison.start.enabled"},havingValue="true")
final class ProductComparisonRecomputeController {
    record Start(long expectedTaskVersion,String inputSha,String configurationSha) { }
    private final ProductComparisonLineageProjection lineage;
    private final ProductComparisonStartApplication starts;
    private final ProductComparisonStartCommands commands;
    private final ProductWorkerDelegations authority;
    private final ProductComparisonJson json;
    private final Clock clock;
    @Autowired ProductComparisonRecomputeController(ProductComparisonLineageProjection lineage,ProductComparisonStartApplication starts,
            ProductComparisonStartCommands commands,ProductWorkerDelegations authority,ProductComparisonJson json) {this(lineage,starts,commands,authority,json,Clock.systemUTC());}
    ProductComparisonRecomputeController(ProductComparisonLineageProjection lineage,ProductComparisonStartApplication starts,
            ProductComparisonStartCommands commands,ProductWorkerDelegations authority,ProductComparisonJson json,Clock clock) {
        this.lineage=lineage;this.starts=starts;this.commands=commands;this.authority=authority;this.json=json;this.clock=clock;
    }
    @PostMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/candidates/{candidateId}/start-commands/recompute")
    ProductComparisonStartApplication.Receipt start(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String candidateId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody String body,@AuthenticationPrincipal Jwt human) {
        var request=json.request(body,Start.class);
        if(request==null||request.expectedTaskVersion()<1||!hash(request.inputSha())||!hash(request.configurationSha()))throw invalid();
        authority.requireComparisonActor(taskId,human);
        if(human==null||human.getIssuer()==null||human.getSubject()==null)throw invalid();
        var actor=new ProductComparisonStartCommands.Actor(human.getIssuer().toString(),human.getSubject());
        // An existing key observes its original command. Expired planning evidence must not trigger replacement dispatch.
        if(commands.lookupOriginalKeyMetadata(taskId,actor,key).isEmpty()) {
            var plan=lineage.read(taskId,comparisonId,human);
            if(!taskId.equals(plan.taskId())||!comparisonId.equals(plan.comparisonId()))throw unavailable();
            requirePending(plan,request.inputSha(),request.configurationSha(),candidateId,clock.instant());
        }
        return starts.start(taskId,comparisonId,candidateId,new ProductComparisonStartApplication.Start(request.expectedTaskVersion(),request.inputSha()),key,human);
    }
    static void requirePending(ProductComparisonLineageProjection.View plan,String inputSha,String configSha,String candidate,Instant now) {
        if(!inputSha.equals(plan.inputSha())||!configSha.equals(plan.configurationSha())||!plan.expiresAt().isAfter(now))throw unavailable();
        var expected=plan.expectedRows().stream().filter(r->r.candidateId().equals(candidate)).toList();
        var pending=plan.recompute().stream().filter(r->r.candidateId().equals(candidate)).toList();
        if(expected.isEmpty()||new HashSet<>(expected).size()!=expected.size()||new HashSet<>(pending).size()!=pending.size()
                ||!new HashSet<>(expected).equals(new HashSet<>(pending))||plan.carried().stream().anyMatch(r->r.key().candidateId().equals(candidate)))throw unavailable();
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalidRequest(){return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("code","INVALID_COMPARISON_RECOMPUTE"));}
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) ResponseEntity<Map<String,String>> rejected(){return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of("code","COMPARISON_RECOMPUTE_UNAVAILABLE"));}
    private static boolean hash(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_COMPARISON_RECOMPUTE");}
    private static IllegalStateException unavailable(){return new IllegalStateException("COMPARISON_RECOMPUTE_UNAVAILABLE");}
}
