package cn.xinji.agentplatform.enforcement.deployment;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Servlet component evidence only: not DM8, OAuth, browser or business acceptance. */
class ControlledReadFilterTest {
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"),ZoneOffset.UTC);
    static ControlledReadPolicy policy(boolean consent) {
        return new ControlledReadPolicy("a".repeat(64),"https://127.0.0.1:5444","ORG-TEST",
                "TEST-CLIENT","https://127.0.0.1:5174/app/enforcement/document-drafts",
                "ISOLATED-DOCUMENT",1,"ISOLATED-PACK",Instant.parse("2026-10-05T00:00:00Z"),
                consent,Set.of("/app/assets/main-abc.js"));
    }
    static MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);request.setSecure(true);return request;
    }
    private int execute(MockHttpServletRequest request,boolean allowed) throws Exception {
        var count=new AtomicInteger();var response=new MockHttpServletResponse();
        new ControlledReadFilter(policy(false),CLOCK).doFilter(request,response,(a,b)->count.incrementAndGet());
        assertEquals(allowed?1:0,count.get());assertEquals(allowed?200:403,response.getStatus());
        assertEquals("a".repeat(64),response.getHeader("X-Deployment-Policy-SHA256"));
        if(!allowed)assertEquals("{\"code\":\"DEPLOYMENT_REQUEST_RESTRICTED\"}",response.getContentAsString());
        return count.get();
    }
    @Test void exactExistingVersionReadPermitted() throws Exception {
        var r=request("GET",policy(false).documentPath());r.setQueryString("revision=1");r.addParameter("revision","1");execute(r,true);
    }
    @Test void unknownVersionAndDuplicateQueryDenied() throws Exception {
        var r=request("GET",policy(false).documentPath());r.setQueryString("revision=2");r.addParameter("revision","2");execute(r,false);
        r.setParameter("revision","1","1");r.setQueryString("revision=1&revision=1");execute(r,false);
    }
    @ParameterizedTest @ValueSource(strings={"/generate","/prepare","/revisions","/candidates","/adopt","/reviews","/submit-review","/exports"})
    void allDocumentCommandsDenied(String suffix)throws Exception {execute(request("POST",policy(false).documentPath()+suffix),false);}
    @ParameterizedTest @ValueSource(strings={"/v2/enforcement/job-packs/collection-tasks/ISOLATED-TASK/claim",
            "/v2/identity/subjects","/v2/identity/grants","/v2/identity/keys/rotate","/v2/identity/clients",
            "/v2/enforcement/job-packs/ISOLATED-PACK/facts/HUMAN_RECEIPT"})
    void claimAndMaintenanceDenied(String path)throws Exception {execute(request("POST",path),false);}
    @ParameterizedTest @ValueSource(strings={"/v2/enforcement/document-drafts/OTHER/history","/v2/enforcement/document-drafts/exports/X/file",
            "/v2/enforcement/job-packs/OTHER","/v2/enforcement/job-packs/collection-tasks","/v2/consultation/tasks"})
    void unknownGetAndExportDenied(String path)throws Exception {execute(request("GET",path),false);}
    @ParameterizedTest @ValueSource(strings={"/v2/identity/%6de","/v2/identity/../identity/me","/v2//identity/me",
            "/v2/identity/me;anything","/v2/identity/%252fme","/v2/identity/./me"})
    void ambiguousPathsDenied(String path)throws Exception {execute(request("GET",path),false);}
    @Test void directAllowedGetStillRejectsPlainHttpAndMethodOverride()throws Exception {
        var r=request("GET","/v2/identity/me");r.setSecure(false);execute(r,false);r.setSecure(true);
        r.addHeader("X-HTTP-Method-Override","POST");execute(r,false);
    }
    @Test void loginBodyNotInspectedByDeploymentFilter()throws Exception {
        var r=new MockHttpServletRequest("POST","/login"){
            @Override public java.util.Map<String,String[]> getParameterMap(){throw new AssertionError("Login password body inspected");}
            @Override public String[] getParameterValues(String name){throw new AssertionError("Login password body inspected");}
        };r.setSecure(true);execute(r,true);
    }
    @Test void normalCodeExchangeAllowedOtherGrantDenied()throws Exception {
        var r=request("POST","/oauth2/token");r.setContentType("application/x-www-form-urlencoded");
        r.addParameter("grant_type","authorization_code");r.addParameter("client_id","TEST-CLIENT");
        r.addParameter("redirect_uri",policy(false).callbackUri());r.addParameter("code","TEST_CODE");r.addParameter("code_verifier","TEST_VERIFIER");
        execute(r,true);r.setParameter("grant_type","client_credentials");execute(r,false);
        r.setParameter("grant_type","authorization_code");r.addParameter("_method","DELETE");execute(r,false);
    }
    @Test void pkceAuthorizationIsExact()throws Exception {
        var r=request("GET","/oauth2/authorize");r.setQueryString("fixture-query");
        r.addParameter("response_type","code");r.addParameter("client_id","TEST-CLIENT");r.addParameter("redirect_uri",policy(false).callbackUri());
        r.addParameter("scope","openid");r.addParameter("state","TEST_STATE");r.addParameter("code_challenge","A".repeat(43));r.addParameter("code_challenge_method","S256");
        execute(r,true);r.setParameter("code_challenge_method","plain");execute(r,false);
        r.setParameter("code_challenge_method","S256");r.setParameter("client_id","OTHER");execute(r,false);
    }
    @Test void forwardCanOnlyRenderPreviouslyPermittedStaticPage()throws Exception {
        var filter=new ControlledReadFilter(policy(false),CLOCK);var r=request("GET",policy(false).callbackPath());
        filter.doFilter(r,new MockHttpServletResponse(),(a,b)->{});
        r.setDispatcherType(DispatcherType.FORWARD);r.setRequestURI("/app/index.html");execute(r,true);
        r.setRequestURI("/v2/enforcement/job-packs/ISOLATED-PACK");execute(r,false);
        var fresh=request("GET","/app/index.html");fresh.setDispatcherType(DispatcherType.FORWARD);execute(fresh,false);
    }
    @Test void errorDispatchRetainsOriginalFailureAndCannotEnterBusiness()throws Exception {
        var filter=new ControlledReadFilter(policy(false),CLOCK);var r=request("GET","/v2/identity/me");
        var response=new MockHttpServletResponse();filter.doFilter(r,response,(a,b)->response.setStatus(503));
        r.setDispatcherType(DispatcherType.ERROR);r.setRequestURI("/error");r.setAttribute("jakarta.servlet.error.request_uri","/v2/identity/me");
        filter.doFilter(r,response,(a,b)->{});assertEquals(503,response.getStatus());
        r.setRequestURI(policy(false).jobPackPath());execute(r,false);
    }
    @Test void expiredPolicyDeniesAllIncludingLogin()throws Exception {
        var expired=Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC);var response=new MockHttpServletResponse();
        new ControlledReadFilter(policy(false),expired).doFilter(request("POST","/login"),response,(a,b)->fail("Expired policy reached chain"));
        assertEquals(403,response.getStatus());
    }
    @Test void consentDeniedUnlessExplicitlyPermitted()throws Exception {execute(request("POST","/oauth2/authorize"),false);}
    @Test void unsupportedMethodsAndDispatchDenied()throws Exception {
        execute(request("HEAD","/v2/identity/me"),false);var r=request("GET","/v2/identity/me");r.setDispatcherType(DispatcherType.INCLUDE);execute(r,false);
    }
}
