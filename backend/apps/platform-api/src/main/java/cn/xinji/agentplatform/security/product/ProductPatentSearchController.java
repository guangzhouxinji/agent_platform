package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}")
@ConditionalOnProperty(name = {"product.identity.enabled", "product.source.engine.enabled"},
        havingValue = "true")
public final class ProductPatentSearchController {
    private static final Logger LOG = LoggerFactory.getLogger(ProductPatentSearchController.class);
    public record Submit(String inputId, String delegationId, long fencingToken) { }
    private final ProductPatentSearchExecution searches;

    ProductPatentSearchController(ProductPatentSearchExecution searches) { this.searches = searches; }

    @PostMapping("/executions/{attemptId}/patent-search-result")
    ProductPatentSearchExecution.Receipt submit(@PathVariable String taskId,
            @PathVariable String attemptId, @RequestBody Submit body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request) {
        requireCertificate(body, request);
        return searches.execute(request, taskId, body.inputId(), body.delegationId(), attemptId,
                body.fencingToken(), requestId);
    }

    @GetMapping("/patent-searches/{resultSetId}/results")
    ProductPatentSearchExecution.Page read(@PathVariable String taskId,
            @PathVariable String resultSetId, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Jwt jwt) {
        return searches.read(taskId, resultSetId, pageSize, cursor, jwt);
    }

    private static void requireCertificate(Submit body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("Exact search execution required");
        Object certificate = request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (!request.isSecure() || !(certificate instanceof java.security.cert.X509Certificate[] chain)
                || chain.length == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalid() { }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    void unavailable(RuntimeException failure) {
        LOG.warn("Patent search request unavailable; failureType={}", failure.getClass().getSimpleName());
    }
}
