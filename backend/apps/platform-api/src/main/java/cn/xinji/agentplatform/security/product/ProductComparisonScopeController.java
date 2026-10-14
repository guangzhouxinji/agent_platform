package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v2/consultation/source-contracts/scopes")
@ConditionalOnProperty(name={"product.identity.enabled","product.source.engine.enabled"},havingValue="true")
public final class ProductComparisonScopeController {
    private final ProductComparisonScopeGrants grants;
    public ProductComparisonScopeController(ProductComparisonScopeGrants grants) { this.grants=grants; }
    @PostMapping("/{sourceId}/comparison/authorize")
    public ProductGrants.Receipt authorize(@PathVariable String sourceId,
            @RequestBody ProductComparisonScopeGrants.Input input,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        return grants.authorize(sourceId,input,requestId,request);
    }
    @PostMapping("/{sourceId}/comparison-model/authorize")
    public ProductGrants.Receipt authorizeModelInput(@PathVariable String sourceId,@RequestBody ProductComparisonScopeGrants.Input input,
            @RequestHeader("Idempotency-Key") String requestId,HttpServletRequest request) {
        return grants.authorizeModelInput(sourceId,input,requestId,request);
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }
    @ExceptionHandler(SecurityException.class) @ResponseStatus(HttpStatus.FORBIDDEN)
    void denied() { }
    @ExceptionHandler({IllegalStateException.class,DataAccessException.class}) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
