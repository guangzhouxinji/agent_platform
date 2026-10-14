package cn.xinji.agentplatform.security.product;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/patent-searches/{resultSetId}/selections")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"}, havingValue = "true")
final class ProductPatentSelectionController {
    private final ProductPatentSelection selections;
    ProductPatentSelectionController(ProductPatentSelection selections) { this.selections = selections; }
    @PostMapping
    ProductPatentSelection.Revision select(@PathVariable String taskId, @PathVariable String resultSetId,
            @RequestBody ProductPatentSelection.Command body, @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal Jwt jwt) {
        return selections.select(taskId, resultSetId, body, key, jwt);
    }
    @GetMapping("/{selectionId}")
    ProductPatentSelection.Revision read(@PathVariable String taskId, @PathVariable String resultSetId,
            @PathVariable String selectionId, @AuthenticationPrincipal Jwt jwt) {
        return selections.read(taskId, resultSetId, selectionId, jwt);
    }
    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
