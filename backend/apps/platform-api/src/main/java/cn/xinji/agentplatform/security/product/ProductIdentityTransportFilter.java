package cn.xinji.agentplatform.security.product;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "product.identity.enabled", havingValue = "true")
public final class ProductIdentityTransportFilter extends OncePerRequestFilter {
    private final ProductGrantWatermark watermark;

    public ProductIdentityTransportFilter(ProductGrantWatermark watermark) {
        this.watermark = watermark;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean productTask = "POST".equals(request.getMethod())
                && path.equals("/v2/consultation/tasks");
        productTask |= "GET".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/goals/confirm");
        productTask |= "GET".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/goals/current");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/cancel");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/sessions");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/worker-delegations"
                        + "(/[0-9a-fA-F-]{36}/revoke)?");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/executions");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/executions/reconciliation");
        productTask |= "POST".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/executions/"
                        + "[0-9a-fA-F-]{36}/patent-search-result");
        productTask |= "GET".equals(request.getMethod())
                && path.matches("/v2/consultation/tasks/[0-9a-fA-F-]{36}/patent-searches/"
                        + "[0-9a-fA-F-]{36}/results");
        return !path.startsWith("/oauth2/") && !path.startsWith("/.well-known/")
                && !path.equals("/login") && !path.startsWith("/v2/identity/")
                && !path.startsWith("/v2/consultation/source-contracts/") && !productTask;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (!request.isSecure() || request.getRequestURI().indexOf('%') >= 0
                || request.getRequestURI().indexOf('\\') >= 0) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        try {
            watermark.assertCurrent();
        } catch (RuntimeException unavailable) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
