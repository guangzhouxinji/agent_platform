package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductComparisonInputs.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
final class ProductComparisonInputController {
    private final ProductComparisonInputApplication application;
    private final ProductComparisonJson json;
    ProductComparisonInputController(ProductComparisonInputApplication application,ProductComparisonJson json) {
        this.application=application;this.json=json;
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    Receipt create(@PathVariable String taskId,@RequestHeader("Idempotency-Key") String key,@RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        return Receipt.from(application.create(taskId,json.request(body,Create.class),key,jwt),json);
    }
    @GetMapping("/{comparisonId}")
    Receipt read(@PathVariable String taskId,@PathVariable String comparisonId,@AuthenticationPrincipal Jwt jwt) {
        return Receipt.from(application.read(taskId,comparisonId,jwt),json);
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("code",e.getReason()==null?"COMPARISON_INPUT_REJECTED":e.getReason()));
    }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class})
    ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code","COMPARISON_INPUT_EVIDENCE_UNAVAILABLE"));
    }
}
