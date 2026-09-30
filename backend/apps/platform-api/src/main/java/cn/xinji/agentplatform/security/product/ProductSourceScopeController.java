package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/consultation/source-contracts/scopes")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public final class ProductSourceScopeController {
    public record Authorize(String taskId, String memberSubject, long taskVersion,
                            long sourceRowVersion, Instant expiresAt) { }

    private final ProductSourceScopeGrants scopes;

    public ProductSourceScopeController(ProductSourceScopeGrants scopes) {
        this.scopes = scopes;
    }

    @PostMapping("/{sourceId}/authorize")
    public ProductSourceScopeGrants.Receipt authorize(@PathVariable String sourceId,
            @RequestBody Authorize body, @RequestHeader("Idempotency-Key") String requestId,
            HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Exact scope request required");
        return scopes.authorize(new ProductSourceScopeGrants.Input(body.taskId(), body.memberSubject(),
                sourceId, body.taskVersion(), body.sourceRowVersion(), body.expiresAt(), requestId), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidRequest() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailableCurrentState() { }
}
