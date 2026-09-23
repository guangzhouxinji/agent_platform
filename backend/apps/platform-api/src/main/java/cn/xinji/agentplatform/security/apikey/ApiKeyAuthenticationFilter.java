package cn.xinji.agentplatform.security.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final ApiCredentialRegistry registry;
    private final ApiScopePolicy scopePolicy;
    private final ApiRateLimiter rateLimiter;
    private final ApiAccessAuditSink audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    ApiKeyAuthenticationFilter(ApiCredentialRegistry registry, ApiScopePolicy scopePolicy,
                               ApiRateLimiter rateLimiter, ApiAccessAuditSink audit,
                               ObjectMapper mapper, Clock clock) {
        this.registry = registry;
        this.scopePolicy = scopePolicy;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !scopePolicy.protects(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        String requestId = requestId(request.getHeader("X-Request-Id"));
        response.setHeader("X-Request-Id", requestId);
        String clientId = "-";
        String keyId = "-";
        String outcome = "SUCCEEDED";
        try {
            ApiCredentialRegistry.AuthenticatedClient client = registry.authenticate(
                    request.getHeader("Authorization"), clock.instant());
            clientId = client.credential().clientId();
            keyId = client.credential().keyId();
            if (scopePolicy.requiresOrganization(request)
                    && (client.credential().organizationRef() == null
                    || client.credential().organizationRef().isBlank())) {
                outcome = "ORGANIZATION_REQUIRED";
                writeError(response, 403, "ORGANIZATION_REQUIRED", requestId);
                return;
            }
            String requiredScope = scopePolicy.requiredScope(request);
            if (!client.allows(requiredScope)) {
                outcome = "SCOPE_DENIED";
                writeError(response, 403, "INSUFFICIENT_SCOPE", requestId);
                return;
            }
            if (!rateLimiter.allow(client.credential(), clock.instant())) {
                outcome = "RATE_LIMITED";
                response.setHeader("Retry-After", "60");
                writeError(response, 429, "RATE_LIMITED", requestId);
                return;
            }
            TrustedClientRequest trusted = new TrustedClientRequest(request,
                    client.credential().principalRef(), client.credential().organizationRef(), clientId, requestId);
            trusted.setAttribute("agentPlatform.trustedClientId", clientId);
            chain.doFilter(trusted, response);
            if (response.getStatus() >= 400) outcome = "DOWNSTREAM_" + response.getStatus();
        } catch (ApiAccessFailure failure) {
            clientId = failure.clientId();
            keyId = failure.keyId();
            outcome = failure.code();
            writeError(response, failure.status(), failure.code(), requestId);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            audit.record(new ApiAccessAuditSink.ApiAccessAuditEvent(requestId, clientId, keyId,
                    request.getMethod(), request.getRequestURI(), outcome, response.getStatus(), durationMs));
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String requestId) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        mapper.writeValue(response.getWriter(), Map.of("code", code, "requestId", requestId));
    }

    private static String requestId(String supplied) {
        return supplied != null && REQUEST_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }
}
