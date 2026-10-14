package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled"},havingValue="true")
final class ProductComparisonMatrixController {
    record Claim(String inputSha,String delegationId,long durationMillis) { }
    record Execute(String comparisonId,String candidateId,String inputSha,String delegationId,long token,Instant leaseUntil) { }
    private final ProductComparisonCandidateExecution leases;private final ProductComparisonMatrixExecution execution;
    private ProductComparisonWorkerMaterials materials;
    private final ProductComparisonMatrixReader reader;private final ProductComparisonJson json;
    ProductComparisonMatrixController(ProductComparisonCandidateExecution leases,ProductComparisonMatrixExecution execution,ProductComparisonMatrixReader reader,ProductComparisonJson json){this.leases=leases;this.execution=execution;this.reader=reader;this.json=json;}
    @org.springframework.beans.factory.annotation.Autowired
    ProductComparisonMatrixController(ProductComparisonCandidateExecution leases,org.springframework.beans.factory.ObjectProvider<ProductComparisonMatrixExecution> execution,ProductComparisonMatrixReader reader,ProductComparisonJson json,ProductComparisonWorkerMaterials materials){this(leases,execution.getIfAvailable(),reader,json);this.materials=materials;}
    private void computation(HttpServletRequest request,ProductComparisonCandidateExecution.Scope scope){
        if(execution==null){materials.consume(request,scope.task(),scope.comparison(),scope.candidate(),scope.inputSha(),scope.delegation());throw new IllegalStateException(ProductComparisonHandoffStore.ERROR);}
    }
    @PostMapping("/patent-comparisons/{comparisonId}/candidates/{candidateId}/executions")
    ProductComparisonCandidateExecution.Lease claim(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String candidateId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody String body,HttpServletRequest request) {
        var c=json.request(body,Claim.class);var scope=new ProductComparisonCandidateExecution.Scope(taskId,comparisonId,candidateId,c.inputSha(),c.delegationId());var duration=Duration.ofMillis(c.durationMillis());computation(request,scope);execution.authorizeClaim(request,scope,duration);return leases.acquire(request,scope,key,duration);
    }
    @PostMapping("/executions/{attemptId}/patent-comparison-result")
    ProductComparisonMatrixResults.Receipt execute(@PathVariable String taskId,@PathVariable String attemptId,@RequestBody String body,HttpServletRequest request) {
        var c=json.request(body,Execute.class);var scope=new ProductComparisonCandidateExecution.Scope(taskId,c.comparisonId(),c.candidateId(),c.inputSha(),c.delegationId());computation(request,scope);return execution.execute(request,scope,new ProductComparisonCandidateExecution.Lease(attemptId,c.token(),c.leaseUntil()));
    }
    @GetMapping("/patent-comparisons/{comparisonId}/results")
    ProductComparisonMatrixReader.Results results(@PathVariable String taskId,@PathVariable String comparisonId,@AuthenticationPrincipal Jwt jwt){return reader.results(taskId,comparisonId,jwt);}
    @GetMapping("/patent-comparisons/{comparisonId}/reports/{reportId}")
    ProductComparisonMatrixResults.Report report(@PathVariable String taskId,@PathVariable String comparisonId,@PathVariable String reportId,@AuthenticationPrincipal Jwt jwt){return reader.report(taskId,comparisonId,reportId,jwt);}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(){return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMPARISON_EXECUTION"));}
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) ResponseEntity<Map<String,String>> unavailable(){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code","COMPARISON_EXECUTION_OR_RESULT_UNAVAILABLE"));}
}


