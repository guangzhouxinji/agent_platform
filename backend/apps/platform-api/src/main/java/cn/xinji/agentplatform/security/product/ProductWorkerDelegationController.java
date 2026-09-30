package cn.xinji.agentplatform.security.product;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/worker-delegations")
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductWorkerDelegationController {
    public record Issue(String serviceSubject, Instant expiresAt) { }
    public record Revoke(long expectedVersion) { }

    private final ProductWorkerDelegations delegations;

    public ProductWorkerDelegationController(ProductWorkerDelegations delegations) {
        this.delegations = delegations;
    }

    @PostMapping
    ProductWorkerDelegations.Receipt issue(@PathVariable String taskId, @RequestBody Issue input,
            @RequestHeader("Idempotency-Key") String requestId, @AuthenticationPrincipal Jwt human) {
        if (human == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return delegations.issue(taskId, input.serviceSubject(), input.expiresAt(), human, requestId);
    }

    @PostMapping("/{id}/revoke")
    ProductWorkerDelegations.Receipt revoke(@PathVariable String taskId, @PathVariable String id,
            @RequestBody Revoke input, @RequestHeader("Idempotency-Key") String requestId,
            @AuthenticationPrincipal Jwt human) {
        if (human == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (input == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return delegations.revoke(taskId, id, input.expectedVersion(), human, requestId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
