package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductHumanTaskFactsFixture.migrate;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Real production assembly, signed identity and both SQL stores. Inputs are component-owned only. */
class ProductPromptCreateHttpTest {
    @TempDir Path originals;
    ProductPromptWorkspaceAuthorizationFixture f;
    ProductPromptInputPermissionFixture input;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String bindingSha,command;

    @BeforeEach void setup()throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(originals);f.grant(Action.CREATE);
        migrate(f.authoritySource,"identity-authority/V008__product_prompt_create_request.sql");
        input=new ProductPromptInputPermissionFixture(f);command=f.mapper.writeValueAsString(input.command);
        bindingSha=f.write("bindings.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        open();
    }
    void open(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("prompt-create-component",Map.ofEntries(
                Map.entry("product.identity.enabled","true"),
                Map.entry("product.identity.issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER),
                Map.entry("product.identity.audience",ProductPromptWorkspaceAuthorizationFixture.AUDIENCE),
                Map.entry("product.identity.master-key",f.masterKeyBase64),
                Map.entry("product.identity.token-verification-window","PT5M"),
                Map.entry("product.consultation.prompt.enabled","true"),
                Map.entry("product.consultation.prompt.create-enabled","true"),
                Map.entry("product.consultation.prompt.evidence-root",originals.toString()),
                Map.entry("product.consultation.prompt.bindings-ref","bindings.json"),
                Map.entry("product.consultation.prompt.bindings-sha256",bindingSha),
                Map.entry("product.consultation.prompt.input-permissions-ref",input.directoryRef),
                Map.entry("product.consultation.prompt.input-permissions-sha256",input.directorySha))));
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("fixture",f));
        context.register(Node.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}

    @Test void createsReplaysAndReopensOnlyOriginalLimitedReceiptWithoutGrantingRead()throws Exception{
        JsonNode original=perform(create("original"),200);
        var fields=new ArrayList<String>();original.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("receiptId","workspaceId","promptId","revisionId","generation",
                "contentSha","resultSha","submittedAt","auditUntil");
        assertThat(original.path("generation").asLong()).isEqualTo(1);
        assertThat(original.path("workspaceId").asText()).isEqualTo(f.workspaceId);
        assertThat(original.path("contentSha").asText()).isEqualTo(input.command.contentSha());
        assertThat(perform(create("original"),200)).isEqualTo(original);
        close();open();
        assertThat(perform(lookup("original"),200)).isEqualTo(original);
        assertThat(perform(create("original"),200)).isEqualTo(original);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_request",String.class)).isEqualTo("READY");
        perform(auth(get("/v2/consultation/prompts/"+original.path("promptId").asText()+"/revisions/"+original.path("revisionId").asText())),403);
        assertThat(f.bodyReads.get()).isZero();
    }
    @Test void originalKeyDifferentCommandIsConflictAndNewUnlicensedContentIsUnavailable()throws Exception{
        perform(create("same-key"),200);
        String different=f.mapper.readTree(command).deepCopy().toString().replace("Owned draft","Different name");
        assertThat(perform(create("same-key").content(different),409).path("code").asText()).isEqualTo("PROMPT_CREATE_CONFLICT");
        assertThat(perform(create("new-key").content(different),503).path("code").asText()).isEqualTo("PROMPT_UNAVAILABLE");
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"missing","revoked-create","read-only","missing-workspace","service"})
    void absentOrUnauthorizedCoordinatesHaveOneBusinessDenial(String fault)throws Exception{
        var request=lookup("absent");
        switch(fault){
            case "revoked-create"->{perform(create("revoked"),200);f.revoke(Action.CREATE);request=lookup("revoked");}
            case "read-only"->{f.revoke(Action.CREATE);f.grant(Action.READ);request=create("read-only");}
            case "missing-workspace"->request=auth(post("/v2/consultation/prompt-workspaces/"+id()+"/prompts"))
                    .header("Idempotency-Key","missing-workspace").contentType("application/json").content(command);
            case "service"->{f.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");request=create("service");}
        }
        assertThat(perform(request,403).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","signature","revoked-session","expired-session","plain-http"})
    void identityOrTlsFailureNeverCreatesDraft(String fault)throws Exception{
        var request=post(base()+"/prompts").secure(!fault.equals("plain-http"))
                .header("Idempotency-Key","identity").contentType("application/json").content(command);
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("signature")
                ?f.token.substring(0,f.token.length()-12)+"aaaaaaaaaaaa":f.token));
        if(fault.equals("revoked-session"))f.db.update("UPDATE id2_session SET status='REVOKED'");
        if(fault.equals("expired-session"))f.db.update("UPDATE id2_session SET issued_at=?,expires_at=?",
                Timestamp.from(Instant.now().minusSeconds(600)),Timestamp.from(Instant.now().minusSeconds(1)));
        perform(request,fault.equals("plain-http")?403:401);assertEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"scope","permission","bindings","sql","registered-missing"})
    void permissionEvidenceAndStorageFailureCloseWithSanitizedUnavailable(String fault)throws Exception{
        switch(fault){
            case "scope"->f.independent.update("UPDATE idp_workspace SET scope_version=scope_version+1");
            case "permission"->Files.writeString(originals.resolve(input.permissionRef),"{}",StandardCharsets.UTF_8);
            case "bindings"->Files.writeString(originals.resolve("bindings.json"),"{}",StandardCharsets.UTF_8);
            case "sql"->f.db.execute("ALTER TABLE conp_revision RENAME TO conp_revision_unavailable");
            case "registered-missing"->f.db.update("DELETE FROM conp_workspace WHERE workspace_id=?",f.workspaceId);
        }
        assertThat(perform(create("unavailable"),503).toString()).isEqualTo("{\"code\":\"PROMPT_UNAVAILABLE\"}");
        if(fault.equals("registered-missing")){
            perform(lookup("unavailable"),503);assertEmpty();
        }
    }
    @Test void unknownCommittedClosureIsUnavailableWithoutRecreatingAnything()throws Exception{
        perform(create("unknown"),200);
        f.db.update("UPDATE conp_event SET result_sha=?","a".repeat(64));
        assertThat(perform(lookup("unknown"),503).toString()).isEqualTo("{\"code\":\"PROMPT_UNAVAILABLE\"}");
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_revision",Integer.class)).isEqualTo(1);
        assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"missing-key","duplicate-key","space-key","unicode-key","long-key","query","bad-uuid","unknown-json","duplicate-json","coerced-json","malformed-template","malformed-utf8","empty-json"})
    void malformedPostIsRejectedBeforeAnyReserve(String fault)throws Exception{
        var request=create("valid");
        switch(fault){
            case "missing-key"->request=auth(post(base()+"/prompts")).contentType("application/json").content(command);
            case "duplicate-key"->request.header("Idempotency-Key","second");
            case "space-key"->request=create("has space");
            case "unicode-key"->request=create("汉字");
            case "long-key"->request=create("x".repeat(65));
            case "query"->request.queryParam("unexpected","1");
            case "bad-uuid"->request=auth(post("/v2/consultation/prompt-workspaces/NOT-A-UUID/prompts"))
                    .header("Idempotency-Key","valid").contentType("application/json").content(command);
            case "unknown-json"->request.content(command.substring(0,command.length()-1)+",\"unexpected\":true}");
            case "duplicate-json"->request.content(command.substring(0,command.length()-1)+",\"name\":\"duplicate\"}");
            case "coerced-json"->request.content(command.replace("\"expectedGeneration\":0","\"expectedGeneration\":\"0\""));
            case "malformed-template"->{var tree=(com.fasterxml.jackson.databind.node.ObjectNode)f.mapper.readTree(command);tree.put("body","unclosed {{");request.content(f.mapper.writeValueAsBytes(tree));}
            case "malformed-utf8"->request.content(new byte[]{(byte)0xc3,0x28});
            case "empty-json"->request.content(new byte[0]);
        }
        perform(request,400);assertEmpty();
    }
    @ParameterizedTest @ValueSource(strings={"text/plain","application/problem+json","application/json;charset=UTF-16"})
    void unexpectedMediaTypeIsUnsupported(String type)throws Exception{
        perform(create("media").contentType(type),415);assertEmpty();
    }
    @Test void absentMediaTypeIsUnsupported()throws Exception{
        perform(auth(post(base()+"/prompts")).header("Idempotency-Key","media").content(command),415);assertEmpty();
    }
    @Test void exactOneMiBIsAcceptedAndNextByteIsPayloadTooLarge()throws Exception{
        int padding=1024*1024-command.getBytes(StandardCharsets.UTF_8).length;
        String maximum=command+" ".repeat(padding);
        perform(create("too-large").content(maximum+" "),413);assertEmpty();
        perform(create("maximum").content(maximum),200);
    }
    @ParameterizedTest @ValueSource(strings={"duplicate-key","query","body"})
    void lookupRejectsExtraTransportInputs(String fault)throws Exception{
        var request=lookup("missing");
        switch(fault){
            case "duplicate-key"->request.header("Idempotency-Key","second");
            case "query"->request.queryParam("key","other");
            case "body"->request.content("{}");
        }
        perform(request,400);assertEmpty();
    }
    private void assertEmpty(){
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_prompt",Integer.class)).isZero();
        assertThat(f.independent.queryForObject("SELECT COUNT(*) FROM idp_prompt_request",Integer.class)).isZero();
    }
    private String base(){return "/v2/consultation/prompt-workspaces/"+f.workspaceId;}
    private MockHttpServletRequestBuilder create(String key){return auth(post(base()+"/prompts")).header("Idempotency-Key",key).contentType("application/json").content(command);}
    private MockHttpServletRequestBuilder lookup(String key){return auth(get(base()+"/requests/CREATE")).header("Idempotency-Key",key);}
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request){return request.secure(true).header("Authorization","Bearer "+f.token);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int status)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String body=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(body).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(body).doesNotContain(f.token,originals.toString(),input.permissionRef,"Ratio {{ratio}}","keySha","issuer","subject");
        return body.isBlank()?f.mapper.createObjectNode():f.mapper.readTree(body);
    }
    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({ProductPromptCreateConfiguration.class,ProductPromptCreateController.class,ProductPromptReadConfiguration.class,
            ProductPromptReadController.class,PromptRouteSecurityConfiguration.class,ExistingRouteSecurityConfiguration.class})
    static class Node implements WebMvcConfigurer{
        @Bean JdbcTemplate db(ProductPromptWorkspaceAuthorizationFixture f){return f.db;}
        @Bean DataSourceTransactionManager manager(ProductPromptWorkspaceAuthorizationFixture f){return f.mainManager;}
        @Bean ProductGrantWatermark watermark(ProductPromptWorkspaceAuthorizationFixture f){return f.watermark;}
        @Bean JwtDecoder jwtDecoder(ProductPromptWorkspaceAuthorizationFixture f){return f.decoder;}
        @Bean ObjectMapper mapper(ProductPromptWorkspaceAuthorizationFixture f){return f.mapper;}
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters){
            converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)));
        }
    }
}
