package cn.xinji.agentplatform.security.product;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/worker-delegations")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
final class ProductComparisonWorkerDelegationController {
    record Revoke(long expectedVersion) { }
    private final ProductComparisonWorkerDelegations application;
    private final ProductComparisonJson json;
    ProductComparisonWorkerDelegationController(ProductComparisonWorkerDelegations application,ProductComparisonJson json) {
        this.application=application;this.json=json;
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductComparisonWorkerDelegations.Receipt issue(@PathVariable String taskId,@PathVariable String comparisonId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody String body,@AuthenticationPrincipal Jwt human) {
        authenticated(human);
        return application.issue(taskId,comparisonId,json.request(body,ProductComparisonWorkerDelegations.Issue.class),human,key);
    }
    @GetMapping("/{delegationId}")
    ProductComparisonWorkerDelegations.Receipt read(@PathVariable String taskId,@PathVariable String comparisonId,
            @PathVariable String delegationId,@AuthenticationPrincipal Jwt human) {
        authenticated(human);return application.read(taskId,comparisonId,delegationId,human);
    }
    @PostMapping(path="/{delegationId}/revoke",consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductComparisonWorkerDelegations.Receipt revoke(@PathVariable String taskId,@PathVariable String comparisonId,
            @PathVariable String delegationId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,
            @AuthenticationPrincipal Jwt human) {
        authenticated(human);
        return application.revoke(taskId,comparisonId,delegationId,json.request(body,Revoke.class).expectedVersion(),human,key);
    }
    private static void authenticated(Jwt human) {
        if (human==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"CURRENT_HUMAN_REQUIRED");
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getReason()==null?"COMPARISON_DELEGATION_REJECTED":e.getReason()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMPARISON_DELEGATION"));
    }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","COMPARISON_DELEGATION_EVIDENCE_UNAVAILABLE"));
    }
}
