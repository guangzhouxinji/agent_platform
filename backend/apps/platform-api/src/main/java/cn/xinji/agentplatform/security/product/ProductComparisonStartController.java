package cn.xinji.agentplatform.security.product;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/candidates/{candidateId}/start-commands")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled","product.consultation.comparison.start.enabled"},havingValue="true")
final class ProductComparisonStartController {
    private final ProductComparisonStartApplication application;
    private final ProductComparisonJson json;
    ProductComparisonStartController(ProductComparisonStartApplication application,ProductComparisonJson json){this.application=application;this.json=json;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ProductComparisonStartApplication.Receipt start(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String candidateId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody String body,@AuthenticationPrincipal Jwt human) {
        return application.start(taskId,comparisonId,candidateId,json.request(body,ProductComparisonStartApplication.Start.class),key,human);
    }
    record Latest(ProductComparisonStartApplication.Receipt receipt) { }
    @GetMapping("/latest")
    Latest latest(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String candidateId,@RequestParam String inputSha,@AuthenticationPrincipal Jwt human) {
        return new Latest(application.latest(taskId,comparisonId,candidateId,inputSha,human));
    }    @GetMapping
    ProductComparisonStartApplication.Receipt read(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String candidateId,
            @RequestParam String requestId,@AuthenticationPrincipal Jwt human){return application.read(taskId,comparisonId,candidateId,requestId,human);}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMPARISON_START"));}
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","COMPARISON_START_UNAVAILABLE"));}
}

