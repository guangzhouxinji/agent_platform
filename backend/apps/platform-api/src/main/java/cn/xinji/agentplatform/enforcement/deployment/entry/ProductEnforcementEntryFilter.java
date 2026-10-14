package cn.xinji.agentplatform.enforcement.deployment.entry;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Intersects bundle admission with the original security/permission chains on every dispatcher. */
public final class ProductEnforcementEntryFilter implements Filter {
    private final ProductEnforcementEntryPolicy policy;
    public ProductEnforcementEntryFilter(ProductEnforcementEntryPolicy policy) { this.policy=policy; }
    private boolean admittedErrorDispatch(HttpServletRequest request) {
        if (request.getDispatcherType() != DispatcherType.ERROR || !"/error".equals(request.getRequestURI())) return false;
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        return original instanceof String path && status instanceof Integer code && code >= 400 && code <= 599
                && policy.allows(request.getMethod(), path);
    }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var http=(HttpServletRequest)request;
        if (policy.allows(http.getMethod(), http.getRequestURI()) || admittedErrorDispatch(http)) { chain.doFilter(request,response); return; }
        var out=(HttpServletResponse)response;
        out.setStatus(404); out.setContentType("application/json"); out.setCharacterEncoding("UTF-8");
        out.setHeader("Cache-Control","no-store");
        out.getWriter().write("{\"code\":\"PRODUCT_ROUTE_NOT_AVAILABLE\"}");
    }
}
