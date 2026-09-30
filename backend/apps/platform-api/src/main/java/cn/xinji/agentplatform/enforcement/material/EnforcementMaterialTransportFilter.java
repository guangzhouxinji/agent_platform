package cn.xinji.agentplatform.enforcement.material;

import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class EnforcementMaterialTransportFilter extends OncePerRequestFilter {
    private final ProductGrantWatermark watermark;

    public EnforcementMaterialTransportFilter(ProductGrantWatermark watermark) { this.watermark = watermark; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v2/enforcement/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws IOException, ServletException {
        if (!request.isSecure() || request.getRequestURI().contains("%")
                || request.getRequestURI().contains("\\")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        try { watermark.assertCurrent(); }
        catch (RuntimeException unavailable) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
