package cn.xinji.agentplatform.enforcement.deployment.entry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.mock.web.*;
import jakarta.servlet.DispatcherType;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
class ProductEnforcementEntryFilterTest {
    @Test void disabledRoutesNeverReachSecurityOrDomainPortsOnAnyDispatcher() throws Exception {
        var filter=new ProductEnforcementEntryFilter(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of("assets/entry.js")));
        for(var type:DispatcherType.values())for(String uri:List.of("/api/v0/enforcement/rules","/v2/enforcement/document-drafts/d/agent-runs","/app/enforcement/material","/app/index.html")){
            var req=new MockHttpServletRequest("GET",uri);req.setDispatcherType(type);var res=new MockHttpServletResponse();var calls=new AtomicInteger();
            filter.doFilter(req,res,(a,b)->calls.incrementAndGet());assertEquals(0,calls.get());assertEquals(404,res.getStatus());assertEquals("no-store",res.getHeader("Cache-Control"));assertTrue(res.getContentAsString().contains("PRODUCT_ROUTE_NOT_AVAILABLE"));
        }
    }
    @Test void admittedCallsProceedThroughTheOriginalSecurityChain() throws Exception {
        var filter=new ProductEnforcementEntryFilter(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of("assets/entry.js")));
        var calls=new AtomicInteger();filter.doFilter(new MockHttpServletRequest("GET","/v2/enforcement/job-packs/collection-tasks"),new MockHttpServletResponse(),(a,b)->calls.incrementAndGet());assertEquals(1,calls.get());
    }
    @Test void onlyContainerErrorRenderingForOriginallyAdmittedRequestsPreservesFailureStatus() throws Exception {
        var filter=new ProductEnforcementEntryFilter(new ProductEnforcementEntryPolicy(List.of("job-pack"),List.of()));
        for (var type:DispatcherType.values()) for (int status:List.of(401,403,409,503)) {
            for (String original:List.of("/v2/enforcement/job-packs/pack","/api/v0/enforcement/rules","/app/adjudication")) {
                var req=new MockHttpServletRequest("GET","/error");req.setDispatcherType(type);
                req.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI,original);
                req.setAttribute(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE,status);
                var res=new MockHttpServletResponse();res.setStatus(status);var calls=new AtomicInteger();
                filter.doFilter(req,res,(a,b)->calls.incrementAndGet());
                boolean allowed=type==DispatcherType.ERROR && original.startsWith("/v2/enforcement/job-packs/");
                assertEquals(allowed?1:0,calls.get());assertEquals(allowed?status:404,res.getStatus());
            }
        }
        var absent=new MockHttpServletRequest("GET","/error");absent.setDispatcherType(DispatcherType.ERROR);
        var res=new MockHttpServletResponse();filter.doFilter(absent,res,(a,b)->fail("Missing original must reject"));assertEquals(404,res.getStatus());
    }

}
