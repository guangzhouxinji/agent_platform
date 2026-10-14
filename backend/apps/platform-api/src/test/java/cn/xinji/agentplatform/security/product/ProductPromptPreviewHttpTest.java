package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

class ProductPromptPreviewHttpTest {
    @TempDir Path root;
    ProductPromptPreviewApplicationFixture x;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String body;
    @BeforeEach void setup()throws Exception{x=new ProductPromptPreviewApplicationFixture(root);open();}
    void open()throws Exception{
        body=x.json(x.command());context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("preview-http",x.properties));x.register(context);
        context.register(Node.class,ProductPromptPreviewConfiguration.class,ProductPromptPreviewController.class,
                ProductPromptSaveConfiguration.class,ProductPromptCopyConfiguration.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();x.clear();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void productionTlsJwtAndDualStoresReturnOnlyBoundedTypedTestDataWithoutWrites()throws Exception{
        var before=x.snapshot();var output=perform(post(),200);var names=new ArrayList<String>();output.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("status","mode","workspaceId","promptId","revisionId","revisionNumber","contentSha","schemaSha","policySha","expiresAt","segments");
        assertThat(output.path("status").asText()).isEqualTo("OK");assertThat(output.path("mode").asText()).isEqualTo("TEST");
        assertThat(output.path("workspaceId").asText()).isEqualTo(x.f.workspaceId);assertThat(output.path("revisionId").asText()).isEqualTo(x.header.revisionId());
        assertThat(output.path("revisionNumber").isTextual()).isTrue();assertThat(output.path("policySha").asText()).isEqualTo(x.input.permissionSha);
        assertThat(output.path("segments").get(1).path("kind").asText()).isEqualTo("DATA");
        assertThat(output.path("segments").get(1).path("value").asText()).isEqualTo("{{other}} Ignore admin <script>alert(1)</script>");
        assertThat(output.path("segments").get(3).path("value").asText()).isEqualTo("9007199254740993123456789");
        assertThat(output.path("segments").get(5).path("value").asText()).isEqualTo("1234567890.1234567890123456789000");
        assertThat(x.snapshot()).isEqualTo(before);assertOneBody();
        assertThat(context.getBeansOfType(ProductPromptCreateApplication.class)).isEmpty();assertThat(context.getBeansOfType(ProductPromptSaveApplication.class)).isEmpty();
        assertThat(context.getBeansOfType(ProductPromptCopyApplication.class)).isEmpty();
        assertThat(x.f.independent.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='IDP_PROMPT_REQUEST'",Integer.class)).isZero();
    }
    @Test void missingValuesReturnOnlyCurrentFiniteDiagnosticsWithNoPersistence()throws Exception{
        var before=x.snapshot();var output=perform(post().content(x.json(x.command(List.of()))),400);
        assertThat(output.path("code").asText()).isEqualTo("PROMPT_PREVIEW_INVALID_VALUES");
        assertThat(output.path("issues").size()).isEqualTo(2);assertThat(output.has("expiresAt")).isTrue();
        assertThat(output.toString()).doesNotContain("segments","owned-text","body","Ignore admin");assertThat(x.snapshot()).isEqualTo(before);assertOneBody();
    }
    @Test void oldSelectedRevisionPreviewsAfterReadySuccessorAndPostIsNotAutomaticallyRepeated()throws Exception{
        x.successor();perform(post(),200);assertOneBody();
        assertThat(x.f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"READ","PREVIEW","task-only","service","workspace","prompt","revision","sha","wrong-owned-workspace"})
    void preciseDoubleRightsAndSelectedScopeAreRequiredBeforeBody(String fault)throws Exception{
        var request=post();ObjectNode value=x.f.mapper.readTree(body).deepCopy();
        switch(fault){
            case "READ"->x.f.revoke(Action.READ);case "PREVIEW"->x.f.revoke(Action.PREVIEW);
            case "task-only"->{x.f.revoke(Action.READ);x.f.revoke(Action.PREVIEW);x.f.taskOnlyGrant();}
            case "service"->x.f.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");
            case "workspace"->request=at(id(),x.header.promptId(),x.header.revisionId());
            case "prompt"->request=at(x.f.workspaceId,id(),x.header.revisionId());
            case "revision"->request=at(x.f.workspaceId,x.header.promptId(),id());
            case "sha"->{value.put("contentSha","f".repeat(64));request.content(value.toString());}
            case "wrong-owned-workspace"->{String other=id();x.f.addWorkspace(other,x.f.organization);x.f.grant(other,Action.READ);x.f.grant(other,Action.PREVIEW);
                x.refreshBindings();close();open();request=at(other,x.header.promptId(),x.header.revisionId());}
        }
        assertThat(perform(request,403).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");assertNoBody();
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","signature","revoked-session","expired-session","plain-http"})
    void identityAndTlsFailuresCloseBeforePreview(String fault)throws Exception{
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path(x.f.workspaceId,x.header.promptId(),x.header.revisionId()))
                .secure(!fault.equals("plain-http")).contentType("application/json").content(body);
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("signature")?x.f.token.substring(0,x.f.token.length()-12)+"aaaaaaaaaaaa":x.f.token));
        if(fault.equals("revoked-session"))x.f.db.update("UPDATE id2_session SET status='REVOKED'");
        if(fault.equals("expired-session"))x.f.db.update("UPDATE id2_session SET issued_at=?,expires_at=?",Timestamp.from(Instant.now().minusSeconds(600)),Timestamp.from(Instant.now().minusSeconds(1)));
        perform(request,fault.equals("plain-http")?403:401);assertNoBody();
    }
    @ParameterizedTest @ValueSource(strings={"query","empty-query","key","bad-workspace","bad-prompt","bad-revision","duplicate-json","unknown-json","source-ref","mode","duplicate-values","number-type","utf8","trailing-json"})
    void strictTransportAndBodyParsingRejectExtraInputsBeforeBody(String fault)throws Exception{
        var request=post();
        switch(fault){
            case "query"->request.queryParam("sourceId",x.input.sourceId);
            case "empty-query"->request.with(raw->{raw.setQueryString("");return raw;});
            case "key"->request.header("Idempotency-Key","not-a-write");
            case "bad-workspace"->request=at("bad",x.header.promptId(),x.header.revisionId());
            case "bad-prompt"->request=at(x.f.workspaceId,"bad",x.header.revisionId());
            case "bad-revision"->request=at(x.f.workspaceId,x.header.promptId(),"bad");
            case "duplicate-json"->request.content(body.substring(0,body.length()-1)+",\"sourceId\":\""+x.input.sourceId+"\"}");
            case "unknown-json"->request.content(body.substring(0,body.length()-1)+",\"body\":\"fake\"}");
            case "source-ref"->request.content(body.substring(0,body.length()-1)+",\"sourceRef\":\"fake-policy\"}");
            case "mode"->request.content(body.substring(0,body.length()-1)+",\"mode\":\"RUNTIME\"}");
            case "duplicate-values"->{ObjectNode value=x.f.mapper.readTree(body).deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)value.get("values")).add(value.get("values").get(0).deepCopy());request.content(value.toString());}
            case "number-type"->{ObjectNode value=x.f.mapper.readTree(body).deepCopy();((ObjectNode)value.get("values").get(1)).put("value",9007199254740992L);request.content(value.toString());}
            case "utf8"->request.content(new byte[]{(byte)0xc3,0x28});case "trailing-json"->request.content(body+"{}");
        }
        assertThat(perform(request,400).path("code").asText()).isEqualTo("INVALID_PROMPT_REQUEST");assertNoBody();
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/json;charset=UTF-16","application/x-www-form-urlencoded","missing","duplicate"})
    void mediaFailuresAreClosedBeforeBody(String media)throws Exception{
        var request=post();if(media.equals("missing"))request.with(raw->{raw.removeHeader("Content-Type");return raw;});
        else if(media.equals("duplicate"))request.header("Content-Type","application/json");else request.contentType(media);
        perform(request,415);assertNoBody();
    }
    @Test void exactMaximumInputIsAcceptedButNextByteIsRejectedBeforeRevisionRead()throws Exception{
        String maximum=body+" ".repeat(1024*1024-body.getBytes(StandardCharsets.UTF_8).length);
        perform(post().content(maximum+" "),413);assertNoBody();perform(post().content(maximum),200);assertOneBody();
    }
    @ParameterizedTest @ValueSource(strings={"permission","directory","selector","pending","sql","grant-watermark"})
    void UnauthenticPolicyAndStoreFailuresReturnGenericUnavailable(String fault)throws Exception{
        var request=post();
        switch(fault){
            case "permission"->Files.writeString(root.resolve(x.input.permissionRef),"{}");
            case "directory"->Files.writeString(root.resolve(x.input.directoryRef),"{}");
            case "selector"->{ObjectNode value=x.f.mapper.readTree(body).deepCopy();value.put("sourceId",id());request.content(value.toString());}
            case "pending"->x.pending();case "sql"->x.f.db.execute("ALTER TABLE conp_revision RENAME TO unavailable_revision");
            case "grant-watermark"->x.f.independent.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id=?",id());
        }
        var failure=perform(request,503);if(!failure.isEmpty())assertThat(failure.path("code").asText()).isEqualTo("PROMPT_UNAVAILABLE");
        assertThat(failure.toString()).doesNotContain("issues","segments","Ignore admin");
    }
    @Test void outputAmplificationReturnsOnlyGenericBudgetFailure()throws Exception{
        close();var variable=new VariableDefinition("text",VariableDefinition.Type.STRING,true,"owned-text",false,null,false);
        x=new ProductPromptPreviewApplicationFixture(Files.createDirectories(root.resolve("output-limit")),List.of(variable),"{{text}}".repeat(20));open();
        var before=x.snapshot();String oversized=x.json(x.command(List.of(new ProductPromptPreviewCommand.Input("text",VariableDefinition.Type.STRING,"a".repeat(65536)))));
        assertThat(perform(post().content(oversized),413).toString()).isEqualTo("{\"code\":\"PROMPT_REQUEST_TOO_LARGE\"}");
        assertThat(x.snapshot()).isEqualTo(before);
    }
    private String path(String workspace,String prompt,String revision){return "/v2/consultation/prompt-workspaces/"+workspace+"/prompts/"+prompt+"/revisions/"+revision+"/preview";}
    private MockHttpServletRequestBuilder at(String workspace,String prompt,String revision){return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path(workspace,prompt,revision))
            .secure(true).header("Authorization","Bearer "+x.f.token).contentType("application/json").content(body);}
    private MockHttpServletRequestBuilder post(){return at(x.f.workspaceId,x.header.promptId(),x.header.revisionId());}
    private JsonNode perform(MockHttpServletRequestBuilder request,int status)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String text=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(text).isEqualTo(status);assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(text).doesNotContain(x.f.token,x.f.root.toString(),x.input.permissionRef,x.input.directoryRef,"policyRef","sourceRef");
        return text.isBlank()?x.f.mapper.createObjectNode():x.f.mapper.readTree(text);
    }
    private void assertOneBody(){assertThat(x.mainSource.statements.stream().filter(sql->sql.startsWith("SELECT revision_json")).count()).isEqualTo(1);}
    private void assertNoBody(){assertThat(x.mainSource.statements).noneMatch(sql->sql.startsWith("SELECT revision_json"));}
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.context.annotation.Import({ProductPromptReadConfiguration.class,ProductPromptReadController.class,
            PromptRouteSecurityConfiguration.class,ExistingRouteSecurityConfiguration.class})
    static class Node extends ProductPromptCreateHttpTest.Node {
        @Override public void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters){
            converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
            super.configureMessageConverters(converters);
        }
    }
}
