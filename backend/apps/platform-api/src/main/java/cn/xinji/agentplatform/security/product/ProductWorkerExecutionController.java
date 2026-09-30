package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/executions")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public final class ProductWorkerExecutionController {
    public record Acquire(String inputId, String delegationId) { }
    private final ProductWorkerExecutionRequests requests;

    ProductWorkerExecutionController(ProductWorkerExecutionRequests requests) {
        this.requests = requests;
    }

    @PostMapping
    ProductWorkerExecutionRequests.Receipt acquire(@PathVariable String taskId,
            @RequestBody Acquire body, @RequestHeader("Idempotency-Key") String requestId,
            HttpServletRequest request) {
        requireCertificate(body, request);
        return requests.acquire(request, taskId, body.inputId(), body.delegationId(), requestId);
    }

    @PostMapping("/reconciliation")
    ProductWorkerExecutionRequests.Receipt reconcile(@PathVariable String taskId,
            @RequestBody Acquire body, @RequestHeader("Idempotency-Key") String requestId,
            HttpServletRequest request) {
        requireCertificate(body, request);
        return requests.reconcile(request, taskId, body.inputId(), body.delegationId(), requestId);
    }

    private static void requireCertificate(Acquire body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Exact worker input required");
        Object certificate = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!request.isSecure() || !(certificate instanceof java.security.cert.X509Certificate[] chain)
                || chain.length == 0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable() { }
}
