package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class ProductPromptCopyHttpTest {
    @TempDir Path root;
    ProductPromptCopyHttpFixture fixture;
    ProductPromptWorkspaceAuthorizationFixture f;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String body;
    @BeforeEach void setup()throws Exception{fixture=new ProductPromptCopyHttpFixture(root);f=fixture.f;body=f.mapper.writeValueAsString(fixture.command);open();}
    void open(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("copy-component",fixture.properties));fixture.register(context);
        context.register(ProductPromptCreateHttpTest.Node.class,ProductPromptSaveConfiguration.class,ProductPromptCopyConfiguration.class,
                ProductPromptCopyController.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void copyOnlyHasNineScalarsAndRecoversAcrossContextRestartWithoutTargetRead()throws Exception{
        JsonNode receipt=perform(copy("original"),200);var names=new ArrayList<String>();receipt.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("receiptId","workspaceId","promptId","revisionId","generation","contentSha","resultSha","submittedAt","auditUntil");
        assertThat(receipt.path("generation").isTextual()).isTrue();assertThat(receipt.path("generation").textValue()).isEqualTo("1");
        assertThat(receipt.path("contentSha").textValue()).isEqualTo(fixture.source.contentSha());
        assertThat(receipt.path("workspaceId").textValue()).isEqualTo(fixture.target);
        assertThat(receipt.path("promptId").textValue()).isNotEqualTo(fixture.source.promptId());
        close();open();assertThat(perform(lookup("original"),200)).isEqualTo(receipt);assertThat(perform(copy("original"),200)).isEqualTo(receipt);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(2);
        perform(auth(get("/v2/consultation/prompts/"+receipt.path("promptId").asText()+"/revisions/"+receipt.path("revisionId").asText())),403);
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();
    }
    @Test void differentOriginalCommandIsTypedKeyConflictBeforeReadingSource()throws Exception{
        var original=perform(copy("same"),200);fixture.tracked.mainSource.statements.clear();
        ObjectNode changed=f.mapper.readTree(body).deepCopy();changed.put("sourceContentSha","f".repeat(64));
        assertThat(perform(copy("same").content(changed.toString()),409).path("code").asText()).isEqualTo("PROMPT_COPY_KEY_CONFLICT");
        assertThat(fixture.tracked.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        assertThat(perform(lookup("same"),200)).isEqualTo(original);assertCopyCount(1);
    }
    @Test void allBeforeRetryKeepsOriginalIdentifiersAndDeadlinesAfterMainFailure()throws Exception{
        fixture.tracked.mainSource.failOnce(sql->sql.startsWith("INSERT INTO conp_revision"));perform(copy("before"),503);
        assertThat(fixture.tracked.mainSource.faultTriggered).isTrue();var fixed=fixture.prepared();
        assertThat(fixed.get("STATE")).isEqualTo("PENDING");assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        close();open();var receipt=perform(copy("before"),200);var recovered=fixture.prepared();
        for(String field:new String[]{"REQUEST_ID","PROMPT_ID","TARGET_REVISION_ID","RECEIPT_ID","PREPARED_AT","BODY_UNTIL","AUDIT_UNTIL","SOURCE_RECORD_SHA"})
            assertThat(recovered.get(field)).as(field).isEqualTo(fixed.get(field));
        assertThat(receipt.path("receiptId").textValue()).isEqualTo(fixed.get("RECEIPT_ID"));assertCopyCount(1);
    }
    @Test void committedTargetReconcilesWhileSourceHasPendingSuccessorWithoutSourceJson()throws Exception{
        fixture.tracked.authoritySource.failOnce(sql->sql.startsWith("UPDATE idp_prompt_control SET state='READY'"));perform(copy("after"),503);
        assertThat(fixture.tracked.authoritySource.faultTriggered).isTrue();assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(2);
        fixture.pendingSource();fixture.tracked.mainSource.statements.clear();var recovered=perform(lookup("after"),200);
        assertThat(recovered.path("generation").textValue()).isEqualTo("1");
        assertThat(fixture.tracked.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));
        assertThat(fixture.prepared().get("STATE")).isEqualTo("READY");assertCopyCount(1);
    }
    @ParameterizedTest @ValueSource(strings={"source-read","target-copy","create-only","source-workspace","source-prompt","source-content","target-workspace","wrong-source-query","missing-key","cross-organization"})
    void doubleRightsAndExactScopeFailuresDoNotReserveCopy(String fault)throws Exception{
        var request=copy("denied");ObjectNode changed=f.mapper.readTree(body).deepCopy();
        switch(fault){
            case "source-read"->f.revoke(Action.READ);
            case "target-copy"->fixture.revokeTargetCopy();
            case "create-only"->{fixture.revokeTargetCopy();f.grant(fixture.target,Action.CREATE);}
            case "source-workspace"->request=postAt(id(),fixture.source.promptId(),"denied");
            case "source-prompt"->request=postAt(f.workspaceId,id(),"denied");
            case "source-content"->{changed.put("sourceContentSha","f".repeat(64));request.content(changed.toString());}
            case "target-workspace"->{changed.put("targetWorkspaceId",id());request.content(changed.toString());}
            case "wrong-source-query"->request=lookup("denied").with(raw->{raw.setQueryString("sourceWorkspaceId="+id());return raw;});
            case "missing-key"->request=lookup("absent");
            case "cross-organization"->{String target=id();f.addWorkspace(target,id());f.grant(target,Action.COPY);fixture.refreshBindings();close();open();changed.put("targetWorkspaceId",target);request.content(changed.toString());}
        }
        assertThat(perform(request,403).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");assertNoCopy();
    }
    @Test void lookupWithAuthorizedButIncorrectSourceDoesNotExposeOriginalSource()throws Exception{
        perform(copy("copied"),200);String other=id();f.addWorkspace(other,f.organization);f.grant(other,Action.READ);
        fixture.refreshBindings();close();open();
        assertThat(perform(lookup("copied").with(raw->{raw.setQueryString("sourceWorkspaceId="+other);return raw;}),403).toString())
                .isEqualTo("{\"code\":\"PROMPT_NOT_AVAILABLE\"}");assertCopyCount(1);
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","signature","revoked-session","expired-session","plain-http"})
    void signedIdentityAndTlsFailuresCloseBeforeCopy(String fault)throws Exception{
        var request=post(path(f.workspaceId,fixture.source.promptId())).secure(!fault.equals("plain-http"))
                .header("Idempotency-Key","identity").contentType("application/json").content(body);
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("signature")?f.token.substring(0,f.token.length()-12)+"aaaaaaaaaaaa":f.token));
        if(fault.equals("revoked-session"))f.db.update("UPDATE id2_session SET status='REVOKED'");
        if(fault.equals("expired-session"))f.db.update("UPDATE id2_session SET issued_at=?,expires_at=?",Timestamp.from(Instant.now().minusSeconds(600)),Timestamp.from(Instant.now().minusSeconds(1)));
        perform(request,fault.equals("plain-http")?403:401);assertNoCopy();
    }
    @ParameterizedTest @ValueSource(strings={"header","query","bad-prompt","bad-workspace","unknown-json","duplicate-json","rename","body","coercion","utf8"})
    void malformedPostHasNoCopySideEffects(String fault)throws Exception{
        var request=copy("malformed");
        switch(fault){
            case "header"->request.header("Idempotency-Key","second");
            case "query"->request.queryParam("sourceWorkspaceId",f.workspaceId);
            case "bad-prompt"->request=postAt(f.workspaceId,"bad","malformed");
            case "bad-workspace"->request=postAt("bad",fixture.source.promptId(),"malformed");
            case "unknown-json"->request.content(body.substring(0,body.length()-1)+",\"createdBy\":\"fake\"}");
            case "duplicate-json"->request.content(body.substring(0,body.length()-1)+",\"expectedGeneration\":0}");
            case "rename"->request.content(body.substring(0,body.length()-1)+",\"name\":\"copy\"}");
            case "body"->request.content(body.substring(0,body.length()-1)+",\"body\":\"client replacement\"}");
            case "coercion"->request.content(body.replace("\"expectedGeneration\":0","\"expectedGeneration\":\"0\""));
            case "utf8"->request.content(new byte[]{(byte)0xc3,0x28});
        }
        perform(request,400);assertNoCopy();
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16","application/x-www-form-urlencoded"})
    void unsupportedMediaCannotReachCopy(String media)throws Exception{perform(copy("media").contentType(media),415);assertNoCopy();}
    @Test void boundedBodyAcceptsExactMaximumAndRejectsNextByte()throws Exception{
        String maximum=body+" ".repeat(1024*1024-body.getBytes(StandardCharsets.UTF_8).length);
        perform(copy("oversize").content(maximum+" "),413);assertNoCopy();perform(copy("maximum").content(maximum),200);
    }
    @ParameterizedTest @ValueSource(strings={"missing","empty","duplicate","percent-name","percent-value","semicolon","plus","extra","trailing","uppercase","wrong-field","body","header"})
    void lookupReadsOnlyOneCanonicalRawSourceQueryAndRejectsBody(String fault)throws Exception{
        var request=lookup("absent");String query="sourceWorkspaceId="+f.workspaceId;
        query=switch(fault){
            case "missing"->null;case "empty"->"sourceWorkspaceId=";case "duplicate"->query+"&"+query;
            case "percent-name"->"source%57orkspaceId="+f.workspaceId;case "percent-value"->"sourceWorkspaceId=%"+"61"+f.workspaceId.substring(1);
            case "semicolon"->query+";";case "plus"->query+"+";case "extra"->query+"&key=other";case "trailing"->query+"&";
            case "uppercase"->"sourceWorkspaceId="+f.workspaceId.toUpperCase(java.util.Locale.ROOT);case "wrong-field"->"workspaceId="+f.workspaceId;
            default->query;
        };
        final String rawQuery=query;request.with(raw->{raw.setQueryString(rawQuery);return raw;});
        if(fault.equals("body"))request.content("{}");if(fault.equals("header"))request.header("Idempotency-Key","other");
        perform(request,400);assertNoCopy();
    }
    @ParameterizedTest @ValueSource(strings={"source-pending","copy-permission","store","missing-revision"})
    void damagedSourceOrLicenceIsUnavailableWithoutCopiedRows(String fault)throws Exception{
        var request=copy("unavailable");
        switch(fault){case "source-pending"->fixture.pendingSource();case "copy-permission"->Files.writeString(root.resolve(fixture.copyRef),"{}");
            case "missing-revision"->{ObjectNode changed=f.mapper.readTree(body).deepCopy();changed.put("sourceRevisionId",id());request.content(changed.toString());}
            default->f.db.execute("ALTER TABLE conp_revision RENAME TO unavailable_revision");}
        assertThat(perform(request,503).path("code").asText()).isEqualTo("PROMPT_UNAVAILABLE");assertCopyCount(0);
    }
    @Test void receiptViewRejectsOtherOperationsAndOtherGeneration(){
        assertThatThrownBy(()->ProductPromptCopyController.ReceiptView.from(fixture.source)).isInstanceOf(IllegalStateException.class);
        var r=fixture.source;var invalid=new Receipt(r.id(),r.workspaceId(),r.issuer(),r.subject(),Operation.COPY,r.keySha(),r.requestSha(),r.promptId(),
                r.revisionId(),2,r.contentSha(),r.resultSha(),r.submittedAt(),r.auditUntil());
        assertThatThrownBy(()->ProductPromptCopyController.ReceiptView.from(invalid)).isInstanceOf(IllegalStateException.class);
    }
    private String path(String workspace,String prompt){return "/v2/consultation/prompt-workspaces/"+workspace+"/prompts/"+prompt+"/copies";}
    private MockHttpServletRequestBuilder postAt(String workspace,String prompt,String key){return auth(post(path(workspace,prompt))).header("Idempotency-Key",key).contentType("application/json").content(body);}
    private MockHttpServletRequestBuilder copy(String key){return postAt(f.workspaceId,fixture.source.promptId(),key);}
    private MockHttpServletRequestBuilder lookup(String key){return auth(get("/v2/consultation/prompt-workspaces/"+fixture.target+"/requests/COPY"))
            .header("Idempotency-Key",key).with(raw->{raw.setQueryString("sourceWorkspaceId="+f.workspaceId);return raw;});}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.secure(true).header("Authorization","Bearer "+f.token);}
    private void assertCopyCount(int expected){assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request WHERE operation='COPY'",Integer.class)).isEqualTo(expected);}
    private void assertNoCopy(){assertCopyCount(0);assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int status)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(text).isEqualTo(status);assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(text).doesNotContain(f.token,root.toString(),fixture.copyRef,"Ratio {{ratio}}","keySha","issuer","subject");
        return text.isBlank()?f.mapper.createObjectNode():f.mapper.readTree(text);
    }
}
