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
        // Transport applies to the entire formal Task family, including new Agent/workspace routes.
        // Route authentication and domain grants remain owned by the security chain and domain services.
        boolean productTask = path.equals("/v2/consultation/tasks")
                || path.startsWith("/v2/consultation/tasks/");
        return !path.startsWith("/oauth2/") && !path.startsWith("/.well-known/")
                && !path.equals("/login") && !path.startsWith("/v2/identity/")
                && !path.startsWith("/v2/consultation/source-contracts/") && !productTask;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        try (var observation = ProductPatentSearchTransportObservation.start(request, response)) {
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
            if (observation != null) observation.authorityChecked();
            chain.doFilter(request, response);
            if (observation != null) observation.downstreamReturned();
        }
    }
}
