package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
final class ProductComparisonPreparationController {
    private final ProductComparisonPreparation application;
    private final ProductComparisonJson json;
    ProductComparisonPreparationController(ProductComparisonPreparation application,ProductComparisonJson json) {
        this.application=application;this.json=json;
    }
    @PostMapping(value="/prepare",consumes=MediaType.APPLICATION_JSON_VALUE)
    PreparationView prepare(@PathVariable String taskId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        return application.prepare(taskId,json.request(body,Prepare.class),key,jwt);
    }
    @GetMapping("/preparations/{preparationId}")
    PreparationView readPreparation(@PathVariable String taskId,@PathVariable String preparationId,@AuthenticationPrincipal Jwt jwt) {
        return application.readPreparation(taskId,preparationId,jwt);
    }
    @PostMapping(value="/features/confirm",consumes=MediaType.APPLICATION_JSON_VALUE)
    FeatureView confirm(@PathVariable String taskId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,
            @AuthenticationPrincipal Jwt jwt) {
        return application.confirm(taskId,json.request(body,Confirm.class),key,jwt);
    }
    @GetMapping("/features/{revisionId}")
    FeatureView read(@PathVariable String taskId,@PathVariable String revisionId,@AuthenticationPrincipal Jwt jwt) {
        return application.read(taskId,revisionId,jwt);
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getReason()==null?"COMPARISON_REQUEST_REJECTED":e.getReason()));
    }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","COMPARISON_CURRENT_EVIDENCE_UNAVAILABLE"));
    }
}
