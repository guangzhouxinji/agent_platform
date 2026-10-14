package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
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

class ProductPromptSaveHttpTest {
    @TempDir Path root;
    ProductPromptSaveHttpFixture fixture;
    ProductPromptWorkspaceAuthorizationFixture f;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String body;
    @BeforeEach void setup()throws Exception{fixture=new ProductPromptSaveHttpFixture(root,true);f=fixture.f;body=f.mapper.writeValueAsString(fixture.command);open();}
    void open(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("save-component",fixture.properties));
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("fixture",f));
        context.register(ProductPromptCreateHttpTest.Node.class,ProductPromptSaveConfiguration.class,ProductPromptSaveController.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void saveOnlyHasExactReceiptAndSurvivesRestartWithoutAutomaticRead()throws Exception{
        JsonNode receipt=perform(save("save-original"),200);var fields=new ArrayList<String>();receipt.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("receiptId","workspaceId","promptId","revisionId","generation","contentSha","resultSha","submittedAt","auditUntil");
        assertThat(receipt.path("generation").isTextual()).isTrue();assertThat(receipt.path("generation").textValue()).isEqualTo("2");
        assertThat(receipt.path("promptId").asText()).isEqualTo(fixture.initial.promptId());
        close();open();assertThat(perform(lookup("save-original"),200)).isEqualTo(receipt);assertThat(perform(save("save-original"),200)).isEqualTo(receipt);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(2);
        perform(auth(get("/v2/consultation/prompts/"+fixture.initial.promptId()+"/revisions/"+receipt.path("revisionId").asText())),403);
        assertThat(f.bodyReads.get()).isZero();assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();
    }
    @Test void keyAndBaseConflictsHaveDistinctCodesWithoutAdditionalRows()throws Exception{
        perform(save("same-key"),200);
        ObjectNode changed=f.mapper.readTree(body).deepCopy();changed.put("name","Changed content");
        assertThat(perform(save("same-key").content(changed.toString()),409).path("code").asText()).isEqualTo("PROMPT_SAVE_KEY_CONFLICT");
        assertThat(perform(save("new-key"),409).path("code").asText()).isEqualTo("PROMPT_SAVE_BASE_CONFLICT");
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request",Integer.class)).isEqualTo(1);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"workspace","prompt","read-only","revoked-save","missing-key"})
    void wrongScopeOrCurrentPermissionNeverReserves(String fault)throws Exception{
        var request=save("denied");
        switch(fault){
            case "workspace"->request=auth(post(path(id(),fixture.initial.promptId()))).header("Idempotency-Key","denied").contentType("application/json").content(body);
            case "prompt"->request=auth(post(path(f.workspaceId,id()))).header("Idempotency-Key","denied").contentType("application/json").content(body);
            case "read-only"->{f.revoke(Action.SAVE);f.grant(Action.READ);}
            case "revoked-save"->f.revoke(Action.SAVE);
            case "missing-key"->request=lookup("absent");
        }
        assertThat(perform(request,403).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");assertNoSave();
    }
    @ParameterizedTest @ValueSource(strings={"unmanaged","registered-missing"})
    void lookupDistinguishesUnmanagedScopeFromRegisteredStoreDamage(String fault)throws Exception{
        String workspace=f.workspaceId;
        if(fault.equals("unmanaged"))workspace=id();else f.db.execute("ALTER TABLE conp_workspace RENAME TO unavailable_workspace");
        perform(auth(get("/v2/consultation/prompt-workspaces/"+workspace+"/requests/SAVE")).header("Idempotency-Key","missing"),fault.equals("unmanaged")?403:503);
        assertNoSave();
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","signature","revoked-session","expired-session","plain-http"})
    void realIdentityAndTlsFailuresCloseBeforeSave(String fault)throws Exception{
        var request=post(path(f.workspaceId,fixture.initial.promptId())).secure(!fault.equals("plain-http"))
                .header("Idempotency-Key","identity").contentType("application/json").content(body);
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("signature")?f.token.substring(0,f.token.length()-12)+"aaaaaaaaaaaa":f.token));
        if(fault.equals("revoked-session"))f.db.update("UPDATE id2_session SET status='REVOKED'");
        if(fault.equals("expired-session"))f.db.update("UPDATE id2_session SET issued_at=?,expires_at=?",Timestamp.from(Instant.now().minusSeconds(600)),Timestamp.from(Instant.now().minusSeconds(1)));
        perform(request,fault.equals("plain-http")?403:401);assertNoSave();
    }
    @ParameterizedTest @ValueSource(strings={"header","query","bad-prompt","bad-workspace","unknown-json","duplicate-json","template","utf8"})
    void malformedPostHasNoReservedSideEffects(String fault)throws Exception{
        var request=save("malformed");
        switch(fault){
            case "header"->request.header("Idempotency-Key","second");
            case "query"->request.queryParam("unexpected","1");
            case "bad-prompt"->request=auth(post(path(f.workspaceId,"bad"))).header("Idempotency-Key","malformed").contentType("application/json").content(body);
            case "bad-workspace"->request=auth(post(path("bad",fixture.initial.promptId()))).header("Idempotency-Key","malformed").contentType("application/json").content(body);
            case "unknown-json"->request.content(body.substring(0,body.length()-1)+",\"createdBy\":\"fake\"}");
            case "duplicate-json"->request.content(body.substring(0,body.length()-1)+",\"expectedGeneration\":1}");
            case "template"->{ObjectNode invalid=f.mapper.readTree(body).deepCopy();invalid.put("body","{{unclosed");request.content(invalid.toString());}
            case "utf8"->request.content(new byte[]{(byte)0xc3,0x28});
        }
        perform(request,400);assertNoSave();
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16"})
    void unsupportedMediaCannotReachSave(String type)throws Exception{perform(save("media").contentType(type),415);assertNoSave();}
    @Test void boundedRawBodyRejectsNextByteAndAcceptsExactLimit()throws Exception{
        String maximum=body+" ".repeat(1024*1024-body.getBytes(StandardCharsets.UTF_8).length);
        perform(save("oversized").content(maximum+" "),413);assertNoSave();perform(save("maximum").content(maximum),200);
    }
    @ParameterizedTest @ValueSource(strings={"body","query","header"})
    void lookupRejectsBodyQueryAndDuplicateHeader(String fault)throws Exception{
        var request=lookup("missing");if(fault.equals("body"))request.content("{}");
        if(fault.equals("query"))request.queryParam("prompt",fixture.initial.promptId());if(fault.equals("header"))request.header("Idempotency-Key","other");
        perform(request,400);assertNoSave();
    }
    @Test void revokedPinnedPermissionAndUnknownClosureAreUnavailable()throws Exception{
        perform(save("committed"),200);f.db.update("UPDATE conp_event SET result_sha=? WHERE generation=2","f".repeat(64));
        assertThat(perform(lookup("committed"),503).path("code").asText()).isEqualTo("PROMPT_UNAVAILABLE");
        Files.writeString(root.resolve(fixture.input.permissionRef),"{}");perform(save("another"),503);
    }
    @Test void receiptDtoKeepsLargestGenerationAsExactDecimalString(){
        var r=fixture.initial;var last=new Receipt(r.id(),r.workspaceId(),r.issuer(),r.subject(),Operation.SAVE,r.keySha(),r.requestSha(),r.promptId(),r.revisionId(),Long.MAX_VALUE,
                r.contentSha(),r.resultSha(),r.submittedAt(),r.auditUntil());
        JsonNode output=f.mapper.valueToTree(ProductPromptSaveController.ReceiptView.from(last));
        assertThat(output.path("generation").isTextual()).isTrue();assertThat(output.path("generation").textValue()).isEqualTo("9223372036854775807");
    }
    private String path(String workspace,String prompt){return "/v2/consultation/prompt-workspaces/"+workspace+"/prompts/"+prompt+"/revisions";}
    private MockHttpServletRequestBuilder save(String key){return auth(post(path(f.workspaceId,fixture.initial.promptId()))).header("Idempotency-Key",key).contentType("application/json").content(body);}
    private MockHttpServletRequestBuilder lookup(String key){return auth(get("/v2/consultation/prompt-workspaces/"+f.workspaceId+"/requests/SAVE")).header("Idempotency-Key",key);}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.secure(true).header("Authorization","Bearer "+f.token);}
    private void assertNoSave(){
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request",Integer.class)).isZero();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
    }
    private JsonNode perform(MockHttpServletRequestBuilder request,int status)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(text).isEqualTo(status);assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(text).doesNotContain(f.token,root.toString(),fixture.input.permissionRef,"Ratio {{ratio}}","keySha","issuer","subject");
        return text.isBlank()?f.mapper.createObjectNode():f.mapper.readTree(text);
    }
}
