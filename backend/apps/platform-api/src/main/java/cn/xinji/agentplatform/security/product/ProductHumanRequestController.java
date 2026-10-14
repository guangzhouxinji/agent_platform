package cn.xinji.agentplatform.security.product;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v2/consultation/tasks/{taskId}/human-requests")
@ConditionalOnProperty(name = "product.consultation.human-request.enabled", havingValue = "true")
final class ProductHumanRequestController {
    private final ProductHumanRequestApplication application;
    private final ProductComparisonJson json;

    ProductHumanRequestController(ProductHumanRequestApplication application, ProductComparisonJson json) {
        this.application = application; this.json = json;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ProductHumanRequestView request(@PathVariable String taskId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody String body, @AuthenticationPrincipal Jwt human) {
        String token = token(human);
        requireKey(key);
        application.request(token, taskId, key, json.request(body, ProductHumanRequestApplication.Command.class));
        // A successful commit is retained even if this fresh authorization check rejects the response.
        return ProductHumanRequestView.from(application.lookup(token, taskId, key));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ProductHumanRequestView lookup(@PathVariable String taskId, @RequestParam String requestId,
            @AuthenticationPrincipal Jwt human) {
        String token = token(human);
        requireKey(requestId);
        return ProductHumanRequestView.from(application.lookup(token, taskId, requestId));
    }

    private static void requireKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static String token(Jwt human) {
        if (human == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return human.getTokenValue();
    }

    @ExceptionHandler(ProductHumanRequestConflict.class)
    ResponseEntity<Map<String, String>> conflict() { return failure(HttpStatus.CONFLICT, "HUMAN_REQUEST_KEY_CONFLICT"); }

    private ResponseEntity<Map<String, String>> invalid() { return failure(HttpStatus.BAD_REQUEST, "INVALID_HUMAN_REQUEST"); }

    @ExceptionHandler(JwtException.class)
    ResponseEntity<Map<String, String>> identity() { return failure(HttpStatus.UNAUTHORIZED, "HUMAN_IDENTITY_UNAVAILABLE"); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, DataAccessException.class})
    ResponseEntity<Map<String, String>> unavailable() { return failure(HttpStatus.SERVICE_UNAVAILABLE, "HUMAN_REQUEST_UNAVAILABLE"); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> rejected(ResponseStatusException failure) {
        return switch (failure.getStatusCode().value()) {
            case 400 -> invalid();
            case 401 -> identity();
            case 404 -> failure(HttpStatus.NOT_FOUND, "HUMAN_REQUEST_NOT_FOUND");
            default -> unavailable();
        };
    }

    private static ResponseEntity<Map<String, String>> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("code", code));
    }
}
