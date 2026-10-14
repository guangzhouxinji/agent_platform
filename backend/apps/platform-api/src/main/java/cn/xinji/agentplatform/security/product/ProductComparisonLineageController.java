package cn.xinji.agentplatform.security.product;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled","product.consultation.comparison.enabled"},havingValue="true")
final class ProductComparisonLineageController {
    private final ProductComparisonLineageProjection reader;
    ProductComparisonLineageController(ProductComparisonLineageProjection reader) { this.reader=reader; }
    @GetMapping("/v2/consultation/tasks/{taskId}/patent-comparisons/{comparisonId}/lineage")
    ResponseEntity<ProductComparisonLineageProjection.View> read(@PathVariable String taskId,@PathVariable String comparisonId,@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.read(taskId,comparisonId,jwt));
    }
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class,DataAccessException.class})
    ResponseEntity<Map<String,String>> unavailable() {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of("code","COMPARISON_LINEAGE_UNAVAILABLE"));
    }
}
