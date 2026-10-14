package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Actual production assembly/security/transport/watermark; certificate attributes are MockMvc input only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegisterHttpTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    ProductPromptRegisterHttpFixture fixture;
    ProductPromptRegisterFixture f;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    X509Certificate other;

    @BeforeAll void certificates()throws Exception{
        tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();
        tls.leaf("other","CN=Owned Other HTTP Client","clientAuth",null);
        other=(X509Certificate)tls.load(tls.root.resolve("other.p12")).getCertificate("other");
    }
    @AfterAll void closeCertificates()throws Exception{if(tls!=null)tls.close();}
    @BeforeEach void setup()throws Exception{fixture=new ProductPromptRegisterHttpFixture(root,tls);f=fixture.f;open();}
    @AfterEach void cleanup(){if(fixture!=null)fixture.close();}
    void open(){
        context=fixture.openContext(new MockServletContext());
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(ProductIdentityTransportFilter.class),
                context.getBean(FilterChainProxy.class)).build();
    }
    void reopen(){context.close();open();}

    @Test void productionRegisterAndOriginalLookupExposeOnlySevenFiniteFields()throws Exception{
        var first=perform(register("original"),200);var fields=new ArrayList<String>();first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("requestId","receiptId","workspaceId","state","resultSha","preparedAt","receiptUntil");
        assertThat(first.path("workspaceId").asText()).isEqualTo(f.workspaceId);assertThat(first.path("state").asText()).isEqualTo("READY");
        assertThat(first.path("requestId").asText()).isNotEqualTo(first.path("receiptId").asText());
        assertThat(first.path("resultSha").asText()).matches("[a-f0-9]{64}");
        var main=f.snapshot(MAIN,"conp_workspace_register_receipt");var authority=f.snapshot(AUTHORITY,"idp_workspace_register_request");
        f.mainFault.clear();f.authorityFault.clear();
        assertThat(perform(lookup("original"),200)).isEqualTo(first);assertThat(perform(register("original"),200)).isEqualTo(first);
        assertNoWrites();reopen();assertThat(perform(lookup("original"),200)).isEqualTo(first);
        assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(main);
        assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request")).isEqualTo(authority);
        assertThat(f.count(MAIN,"conp_prompt")).isZero();assertThat(f.count(AUTHORITY,"id2_maintenance_audit")).isZero();
        fixture.watermark.assertCurrent();
    }

    @Test void missingLookupIsUnavailableAndNeverAllocatesRegistration()throws Exception{
        assertCode(lookup("absent"),503,"PROMPT_UNAVAILABLE");assertEmpty();assertNoWrites();
    }
    @Test void differentKeyReturnsFixedConflictAndCannotRegisterAnotherWorkspaceRow()throws Exception{
        perform(register("one"),200);f.mainFault.clear();f.authorityFault.clear();
        assertCode(register("two"),409,"PROMPT_REGISTER_CONFLICT");assertCode(lookup("two"),409,"PROMPT_REGISTER_CONFLICT");
        assertNoWrites();assertThat(f.count(MAIN,"conp_workspace")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"POST","GET"})
    void noCertificateCannotBeReplacedByValidOrdinaryJwt(String method)throws Exception{
        String token=fixture.signedToken();var request=request(method,"jwt-only").requestAttr("jakarta.servlet.request.X509Certificate",new X509Certificate[0])
                .header("Authorization","Bearer "+token);
        assertCode(request,403,"PROMPT_NOT_AVAILABLE");assertEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"missing","other","empty","wrong-type","header-only"})
    void containerCertificateInputCannotBeMissingForgedOrAnotherIdentity(String failure)throws Exception{
        var request=post(base()+"/register").secure(true).header("Idempotency-Key","certificate");
        switch(failure){
            case "other"->request.requestAttr("jakarta.servlet.request.X509Certificate",new X509Certificate[]{other});
            case "empty"->request.requestAttr("jakarta.servlet.request.X509Certificate",new X509Certificate[0]);
            case "wrong-type"->request.requestAttr("jakarta.servlet.request.X509Certificate",f.certificateSha);
            case "header-only"->request.header("X-Client-Certificate",f.certificateSha).header("X-Subject",f.subject);
        }
        assertCode(request,403,"PROMPT_NOT_AVAILABLE");assertEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"status='REVOKED'","allowed_roles='SUBJECTS'","version=2",
            "certificate_sha='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'","subject_ref='different-maintainer'"})
    void currentMaintenanceRowMustMatchExactRegistrationApproval(String assignment)throws Exception{
        f.independent.update("UPDATE id2_maintainer SET "+assignment);
        assertCode(register("maintainer"),403,"PROMPT_NOT_AVAILABLE");assertEmpty();
    }
    @Test void unmanagedWorkspaceIsForbiddenWithoutRegistration()throws Exception{
        assertCode(cert(post("/v2/identity/prompt-workspaces/"+UUID.randomUUID()+"/register")).header("Idempotency-Key","unmanaged"),
                403,"PROMPT_NOT_AVAILABLE");assertEmpty();
    }

    @ParameterizedTest @CsvSource({"POST,missing-key","GET,missing-key","POST,empty-key","GET,empty-key","POST,duplicate-key","GET,duplicate-key","POST,space-key","GET,space-key",
            "POST,unicode-key","GET,unicode-key","POST,too-long","GET,too-long","POST,query","GET,query","POST,empty-query","GET,empty-query",
            "POST,json-body","GET,json-body","POST,form-body","GET,form-body","POST,binary-body","GET,binary-body","POST,bad-uuid","GET,bad-uuid"})
    void strictInputRejectsAnyExtraCoordinatesOrBodyBeforeApplication(String method,String failure)throws Exception{
        var request=request(method,"strict");
        switch(failure){
            case "missing-key"->request=cert(method.equals("POST")?post(base()+"/register"):get(base()+"/requests/REGISTER"));
            case "empty-key"->request=at(method,f.workspaceId,"");
            case "duplicate-key"->request.header("Idempotency-Key","other");
            case "space-key"->request=at(method,f.workspaceId,"has space");
            case "unicode-key"->request=at(method,f.workspaceId,"\u4e2d\u6587");
            case "too-long"->request=at(method,f.workspaceId,"x".repeat(65));
            case "query"->request.queryParam("organization",f.organization);
            case "empty-query"->request.with(raw->{raw.setQueryString("");return raw;});
            case "json-body"->request.contentType("application/json").content("{}");
            case "form-body"->request.contentType("application/x-www-form-urlencoded").content("identity=forged");
            case "binary-body"->request.contentType("application/octet-stream").content(new byte[]{0});
            case "bad-uuid"->request=at(method,"NOT-A-UUID","strict");
        }
        assertCode(request,400,"INVALID_PROMPT_REQUEST");assertEmpty();
        assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("SELECT receipt_until FROM idp_workspace_register_request"));
        assertNoWrites();
    }
    @Test void maximumVisibleAsciiOriginalKeyIsAcceptedWithoutBodyOrContentType()throws Exception{
        String key="x".repeat(64);var result=perform(register(key),200);assertThat(perform(lookup(key),200)).isEqualTo(result);
    }

    @Test void headLookupRefusesBeforeIdentityLeaseAndDoesNotSettlePending()throws Exception{
        f.failSql(MAIN,sql->sql.startsWith("INSERT INTO conp_workspace_register_receipt"));
        assertCode(register("head"),503,"PROMPT_UNAVAILABLE");assertThat(f.state()).isEqualTo("PENDING");
        f.mainFault.clear();f.authorityFault.clear();
        var response=mvc.perform(cert(head(base()+"/requests/REGISTER")).header("Idempotency-Key","head")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(405);assertThat(response.getHeader("Cache-Control")).contains("no-store");
        if(!response.getContentAsString().isBlank())assertThat(f.mapper.readTree(response.getContentAsString()).path("code").asText()).isEqualTo("PROMPT_METHOD_NOT_ALLOWED");
        assertThat(f.state()).isEqualTo("PENDING");assertNoWrites();
        assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("SELECT receipt_until FROM idp_workspace_register_request"));
    }
    @Test void headRegisterHasNoRegistrationSideEffect()throws Exception{
        assertThat(mvc.perform(cert(head(base()+"/register")).header("Idempotency-Key","head")).andReturn().getResponse().getStatus()).isEqualTo(405);
        assertEmpty();assertNoWrites();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mainCommitUnknownPreservesFactsAndRequiresExplicitNewInstanceLookup(boolean afterRealCommit)throws Exception{
        f.failCommitForSql(MAIN,"INSERT INTO conp_workspace_register_receipt",afterRealCommit);
        assertCode(register("commit-main"),503,"PROMPT_UNAVAILABLE");assertThat(f.state()).isEqualTo("PENDING");
        var original=f.snapshot(AUTHORITY,"idp_workspace_register_request");
        assertCode(lookup("commit-main"),503,"PROMPT_UNAVAILABLE");reopen();
        if(afterRealCommit){perform(lookup("commit-main"),200);assertThat(f.state()).isEqualTo("READY");}
        else{assertCode(lookup("commit-main"),503,"PROMPT_UNAVAILABLE");assertThat(f.state()).isEqualTo("PENDING");}
        assertThat(f.snapshot(AUTHORITY,"idp_workspace_register_request").getFirst().get("REQUEST_ID")).isEqualTo(original.getFirst().get("REQUEST_ID"));
        assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isEqualTo(afterRealCommit?1:0);
    }
    @Test void readyStatementFailureReturnsUnavailableThenOnlyOriginalGetCompletes()throws Exception{
        f.failSql(AUTHORITY,sql->sql.startsWith("UPDATE idp_workspace_register_request SET state='READY'"));
        try(var diagnostic=new FailureLog()){
            var result=mvc.perform(register("ready-failure")).andReturn();var response=result.getResponse();
            assertThat(response.getStatus()).isEqualTo(503);assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(f.mapper.readTree(response.getContentAsString())).isEqualTo(f.mapper.valueToTree(Map.of("code","PROMPT_UNAVAILABLE")));
            assertThat(result.getResolvedException()).hasMessage("Registration transaction result unavailable").hasRootCauseInstanceOf(java.sql.SQLException.class);
            var logged=diagnostic.onlyFailure();assertThat(logged).isSameAs(result.getResolvedException());
            assertThat(logged.getCause()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(f.state()).isEqualTo("PENDING");
        var original=f.snapshot(MAIN,"conp_workspace_register_receipt");perform(lookup("ready-failure"),200);
        assertThat(f.state()).isEqualTo("READY");assertThat(f.snapshot(MAIN,"conp_workspace_register_receipt")).isEqualTo(original);
    }
    @Test void unavailableDiagnosticKeepsOriginalStageCauseAndSuppressedWithoutChangingPublicResponse(){
        var cause=new java.sql.SQLException("owned driver commit failure");
        var cleanup=new java.io.IOException("Registration connection release unknown");
        var failure=new IllegalStateException("Registration transaction result unavailable",cause);failure.addSuppressed(cleanup);
        try(var diagnostic=new FailureLog()){
            var response=context.getBean(ProductPromptRegisterController.class).unavailable(failure);
            assertThat(response.getStatusCode().value()).isEqualTo(503);assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            assertThat(response.getBody()).isEqualTo(Map.of("code","PROMPT_UNAVAILABLE"));
            Throwable logged=diagnostic.onlyFailure();assertThat(logged).isSameAs(failure);
            assertThat(logged.getMessage()).isEqualTo("Registration transaction result unavailable");
            assertThat(logged.getCause()).isSameAs(cause);assertThat(logged.getSuppressed()).containsExactly(cleanup);
        }
    }

    @ParameterizedTest @ValueSource(strings={"register.json","business.json","policy.json","bindings.json"})
    void changedPinnedOriginalReturnsOnlyUnavailableCode(String ref)throws Exception{
        Files.writeString(root.resolve(ref),"{}");assertCode(register("pin-drift"),503,"PROMPT_UNAVAILABLE");assertEmpty();
    }
    @Test void correctlyPinnedButExpiredActionApprovalStillReturnsUnavailable()throws Exception{
        var approval=f.read(f.registerRef);approval.put("validFrom",f.now.minusSeconds(60));approval.put("validUntil",f.now.minusSeconds(1));
        String sha=f.write(f.registerRef,approval);
        fixture.properties.put("product.consultation.prompt.register-approval-pins-json",f.mapper.writeValueAsString(List.of(
                new ProductPromptRegisterApproval.Pin(f.workspaceId,f.registerRef,sha))));
        reopen();f.mainFault.clear();f.authorityFault.clear();
        assertCode(register("expired-approval"),503,"PROMPT_UNAVAILABLE");assertEmpty();assertNoWrites();
    }
    @ParameterizedTest @ValueSource(strings={"receipt","authority","main-receipt","main-closed","main-drift"})
    void priorRegistrationCannotBeRebuiltAfterExpiryMissingOrDriftingFacts(String failure)throws Exception{
        perform(register("closed"),200);
        switch(failure){
            case "receipt"->{var at=Timestamp.from(Instant.now().minusSeconds(1));f.independent.update("UPDATE idp_workspace_register_request SET prepared_at=?,intent_until=?,receipt_until=?",
                    Timestamp.from(at.toInstant().minusSeconds(1)),at,at);}
            case "authority"->f.independent.update("DELETE FROM idp_workspace_register_request");
            case "main-receipt"->f.db.update("DELETE FROM conp_workspace_register_receipt");
            case "main-closed"->f.db.update("UPDATE conp_workspace SET state='CLOSED'");
            case "main-drift"->f.db.update("UPDATE conp_workspace SET scope_version=2");
        }
        f.mainFault.clear();f.authorityFault.clear();assertCode(lookup("closed"),503,"PROMPT_UNAVAILABLE");assertNoWrites();
        if(failure.equals("receipt"))assertThat(f.authorityFault.sql).noneMatch(sql->sql.startsWith("SELECT request_id,"));
    }

    @ParameterizedTest @ValueSource(strings={"insecure","percent-path","pending-watermark","digest-watermark"})
    void realGlobalTransportAndWatermarkRejectBeforeApplication(String failure)throws Exception{
        var request=register("transport");
        switch(failure){
            case "insecure"->request.secure(false);
            case "percent-path"->request.with(raw->{raw.setRequestURI(base()+"/reg%69ster");return raw;});
            case "pending-watermark"->f.independent.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=?",UUID.randomUUID().toString());
            case "digest-watermark"->f.independent.update("UPDATE id2_authority_watermark SET history_sha=?","a".repeat(64));
        }
        f.mainFault.clear();f.authorityFault.clear();
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(failure.equals("insecure")||failure.equals("percent-path")?403:503);
        assertThat(response.getContentAsString()).isBlank();assertEmpty();assertNoWrites();
    }

    private String base(){return "/v2/identity/prompt-workspaces/"+f.workspaceId;}
    private MockHttpServletRequestBuilder cert(MockHttpServletRequestBuilder request){return request.secure(true)
            .requestAttr("jakarta.servlet.request.X509Certificate",new X509Certificate[]{f.certificate});}
    private MockHttpServletRequestBuilder at(String method,String workspace,String key){return cert(method.equals("POST")
            ?post("/v2/identity/prompt-workspaces/"+workspace+"/register"):get("/v2/identity/prompt-workspaces/"+workspace+"/requests/REGISTER"))
            .header("Idempotency-Key",key);}
    private MockHttpServletRequestBuilder request(String method,String key){return at(method,f.workspaceId,key);}
    private MockHttpServletRequestBuilder register(String key){return request("POST",key);}
    private MockHttpServletRequestBuilder lookup(String key){return request("GET",key);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int expected)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(text).isEqualTo(expected);assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(text).doesNotContain(root.toString(),f.registerRef,f.certificateSha,"keySha","operatorSubject","databaseIdentity","approvalSha");
        return f.mapper.readTree(text);
    }
    private void assertCode(MockHttpServletRequestBuilder request,int status,String code)throws Exception{
        var value=perform(request,status);assertThat(value.size()).isEqualTo(1);assertThat(value.path("code").asText()).isEqualTo(code);
    }
    private void assertEmpty(){assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.count(AUTHORITY,"idp_workspace_register_request")).isZero();}
    private void assertNoWrites(){for(var fault:List.of(f.mainFault,f.authorityFault))assertThat(fault.sql)
            .noneMatch(sql->sql.startsWith("INSERT")||sql.startsWith("UPDATE")||sql.startsWith("DELETE"));}
    static final class FailureLog implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ProductPromptRegisterController.class);
        private final ch.qos.logback.classic.Level previous=logger.getLevel();
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> sink=new ch.qos.logback.core.read.ListAppender<>();
        FailureLog(){sink.start();logger.addAppender(sink);logger.setLevel(ch.qos.logback.classic.Level.WARN);}
        Throwable onlyFailure(){
            assertThat(sink.list).hasSize(1);var event=sink.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("Prompt workspace registration unavailable");
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(event.getThrowableProxy()).isInstanceOf(ch.qos.logback.classic.spi.ThrowableProxy.class);
            return ((ch.qos.logback.classic.spi.ThrowableProxy)event.getThrowableProxy()).getThrowable();
        }
        @Override public void close(){logger.detachAppender(sink);sink.stop();logger.setLevel(previous);}
    }
}
