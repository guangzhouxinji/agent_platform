package cn.xinji.agentplatform.enforcement.deployment;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deployment-only request fence; no domain writes or alternative authentication. */
public final class ControlledReadFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(ControlledReadFilter.class);
    private static final String ACCEPTED = ControlledReadFilter.class.getName()+".accepted";
    private final ControlledReadPolicy policy;
    private final ControlledReadRequests requests;
    private final Clock clock;
    public ControlledReadFilter(ControlledReadPolicy policy, Clock clock) {
        this.policy=policy; this.requests=new ControlledReadRequests(policy); this.clock=clock;
    }

    @Override public void doFilter(ServletRequest rawRequest, ServletResponse rawResponse, FilterChain chain)
            throws IOException, ServletException {
        if (!(rawRequest instanceof HttpServletRequest request)
                || !(rawResponse instanceof HttpServletResponse response))
            throw new ServletException("HTTP controlled-read deployment required");
        String route = policy.current(clock) ? route(request) : null;
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Deployment-Policy-SHA256",policy.digest());
        response.setHeader("X-Deployment-Request-ID",requestId);
        LOG.info("ControlledRead route={} result={} request={} policy={}", route==null?"UNLISTED":route,
                route==null?"DENIED":"PERMITTED",requestId,policy.digest());
        if (route == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setHeader("Cache-Control","no-store");
            response.getWriter().write("{\"code\":\"DEPLOYMENT_REQUEST_RESTRICTED\"}");
            return;
        }
        if (request.getDispatcherType()==DispatcherType.REQUEST)
            request.setAttribute(ACCEPTED,request.getRequestURI());
        chain.doFilter(request,response);
    }

    private String route(HttpServletRequest request) {
        String original = request.getAttribute(ACCEPTED) instanceof String value ? value : null;
        if (request.getDispatcherType()==DispatcherType.FORWARD) {
            return request.isSecure() && policy.callbackPath().equals(original)
                    && "/app/index.html".equals(request.getRequestURI()) ? "INTERNAL_STATIC_FORWARD" : null;
        }
        if (request.getDispatcherType()==DispatcherType.ERROR) {
            return request.isSecure() && original!=null && "/error".equals(request.getRequestURI())
                    && original.equals(request.getAttribute("jakarta.servlet.error.request_uri"))
                    ? "INTERNAL_ERROR_RENDER" : null;
        }
        if (request.getDispatcherType()!=DispatcherType.REQUEST
                && request.getDispatcherType()!=DispatcherType.ASYNC) return null;
        return requests.route(request);
    }
}
