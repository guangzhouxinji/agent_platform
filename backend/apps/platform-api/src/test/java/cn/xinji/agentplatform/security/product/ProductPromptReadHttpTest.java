package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceAuthorizationFixture.id;
import static cn.xinji.agentplatform.security.product.ProductPromptWorkspaceEvidence.Action;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** Production read Bean, security chain, signed JWT and two real SQL stores; owned component inputs only. */
class ProductPromptReadHttpTest {
    @TempDir Path originals;
    ProductPromptWorkspaceAuthorizationFixture f;
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    String prompt;
    Receipt first;

    @BeforeEach void setup()throws Exception{
        f=new ProductPromptWorkspaceAuthorizationFixture(originals);f.grant(Action.READ);prompt=id();
        first=append(prompt,1,"Original body",false);
        String digest=f.write("bindings.json",Map.of("format","PROMPT_WORKSPACE_BINDINGS/v1","bindings",List.copyOf(f.bindings.values())));
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("prompt-component",Map.of(
                "product.identity.enabled","true","product.identity.issuer",ProductPromptWorkspaceAuthorizationFixture.ISSUER,
                "product.consultation.prompt.enabled","true","product.consultation.prompt.evidence-root",originals.toString(),
                "product.consultation.prompt.bindings-ref","bindings.json","product.consultation.prompt.bindings-sha256",digest)));
        context.addBeanFactoryPostProcessor(factory->factory.registerSingleton("fixture",f));
        context.register(Node.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
        f.bodyReads.set(0);
    }
    @AfterEach void close(){if(context!=null)context.close();}

    @Test void readsExactOldRevisionAndHistoryThroughProductionAssembly()throws Exception{
        Receipt second=append(prompt,2,"New body",true);
        JsonNode old=perform(auth(exact(first)),200);
        assertThat(old.path("workspaceId").asText()).isEqualTo(f.workspaceId);
        assertThat(old.path("contentSha").asText()).isEqualTo(first.contentSha());
        assertThat(old.path("body").asText()).isEqualTo("Original body");
        assertThat(old.toString()).doesNotContain("approvalRef","policyRef","registrySha",f.token);
        JsonNode current=perform(auth(exact(second)),200);
        assertThat(current.path("workspaceId").asText()).isEqualTo(f.workspaceId);
        assertThat(current.path("contentSha").asText()).isEqualTo(second.contentSha()).isNotEqualTo(first.contentSha());
        assertThat(current.path("version").asLong()).isEqualTo(2);
        assertThat(current.path("variables").get(0).path("defaultValue").asText()).isEqualTo("12345678901234567890.01234567890123456789");
        var versions=perform(auth(base()).param("page","1").param("size","10"),200);
        assertThat(versions.path("total").asLong()).isEqualTo(2);
        assertThat(versions.path("revisions").size()).isEqualTo(2);
        assertThat(versions.toString()).doesNotContain("workspaceId","contentSha");
        assertThat(versions.toString()).doesNotContain("body\"","Original body","New body","issuer","subject");
        assertThat(perform(auth(base()).param("page","2").param("size","10"),200).path("revisions").isEmpty()).isTrue();
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM conp_event",Integer.class)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"anonymous","signature","plainHttp"})
    void identityAndTlsFailuresNeverReachBody(String fault)throws Exception{
        var request=get(exact(first)).secure(!fault.equals("plainHttp"));
        if(!fault.equals("anonymous"))request.header("Authorization","Bearer "+(fault.equals("signature")?f.token.substring(0,f.token.length()-12)+"aaaaaaaaaaaa":f.token));
        perform(request,fault.equals("plainHttp")?403:401);assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"create-only","task-only","service","missing-prompt","missing-revision"})
    void missingAndUnauthorizedUseTheSameBusinessDenial(String fault)throws Exception{
        String path=exact(first);
        switch(fault){
            case "create-only"->{f.revoke(Action.READ);f.grant(Action.CREATE);}
            case "task-only"->{f.revoke(Action.READ);f.taskOnlyGrant();}
            case "service"->f.db.update("UPDATE id2_subject SET subject_kind='SERVICE',password_hash=NULL");
            case "missing-prompt"->path="/v2/consultation/prompts/"+id()+"/revisions/"+first.revisionId();
            case "missing-revision"->path=base()+"/"+id();
        }
        assertThat(perform(auth(path),403).path("code").asText()).isEqualTo("PROMPT_NOT_AVAILABLE");
        assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"EXPIRED","REVOKED"})
    void signedStillValidJwtCannotUseExpiredOrRevokedSession(String state)throws Exception{
        var signed=org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
                .jwtDecoder(f.keys).decode(f.token);
        assertThat(signed.getExpiresAt()).isAfter(Instant.now());
        if(state.equals("REVOKED"))f.db.update("UPDATE id2_session SET status='REVOKED'");
        else f.db.update("UPDATE id2_session SET issued_at=?,expires_at=?",java.sql.Timestamp.from(f.now.minusSeconds(600)),java.sql.Timestamp.from(f.now.minusSeconds(1)));
        perform(auth(exact(first)),401);assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"bindings","approval","pending","record","sql"})
    void evidenceAndStorageFailuresAreUnavailableNotEmptyOrBadClientInput(String fault)throws Exception{
        switch(fault){
            case "bindings"->Files.writeString(originals.resolve("bindings.json"),"{}",StandardCharsets.UTF_8);
            case "approval"->Files.writeString(originals.resolve(f.bindings.get(f.workspaceId).approvalRef()),"{}",StandardCharsets.UTF_8);
            case "pending"->f.independent.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation='SAVE',key_sha=?,request_sha=?,before_sha=scalar_sha,expected_after_sha=?,target_generation=2,target_revision_id=?,intent_until=?",
                    id(),"a".repeat(64),"b".repeat(64),"c".repeat(64),id(),java.sql.Timestamp.from(f.now.plusSeconds(500)));
            case "record"->f.db.update("UPDATE conp_revision SET revision_json='{}'");
            case "sql"->f.db.execute("ALTER TABLE conp_revision RENAME TO conp_revision_unavailable");
        }
        var denied=perform(auth(exact(first)),503);
        assertThat(denied.toString()).isEqualTo("{\"code\":\"PROMPT_UNAVAILABLE\"}");
        assertThat(denied.toString()).doesNotContain("Original body",originals.toString(),f.token);
        if(!fault.equals("record"))assertThat(f.bodyReads.get()).isZero();
    }
    @Test void expiredSelectedBodyIsRejectedBeforeItsJsonIsRead()throws Exception{
        Instant present=f.now;f.now=f.now.minusSeconds(100);
        Receipt expired=append(id(),1,"Expired body",false,30);
        f.now=present;f.bodyReads.set(0);
        String path="/v2/consultation/prompts/"+expired.promptId()+"/revisions/"+expired.revisionId();
        perform(auth(path),403);assertThat(f.bodyReads.get()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"zero-page","wrong-size","not-number","bad-uuid"})
    void malformedCoordinatesAreBadRequests(String fault)throws Exception{
        var request=auth(fault.equals("bad-uuid")?"/v2/consultation/prompts/not-an-id/revisions":base());
        if(fault.equals("zero-page"))request.param("page","0");
        if(fault.equals("wrong-size"))request.param("size","11");
        if(fault.equals("not-number"))request.param("page","unknown");
        perform(request,400);assertThat(f.bodyReads.get()).isZero();
    }
    @Test void boundOuterTransactionRemainsUnavailableNotBusinessDenial(){
        var app=context.getBean(ProductPromptReadApplication.class);
        f.mainTx.executeWithoutResult(status->{
            try{app.revision(f.token,prompt,first.revisionId());throw new AssertionError("nested boundary accepted");}
            catch(IllegalStateException failure){assertThat(failure).isNotInstanceOf(ProductPromptAccessDenied.class);}
        });
        assertThat(f.bodyReads.get()).isZero();
    }
    @Test void registryParsingRejectsCoercionUnknownAndDuplicateKeys()throws Exception{
        String raw=Files.readString(originals.resolve("bindings.json"));
        for(String bad:List.of(raw.replace("\"scopeVersion\":1","\"scopeVersion\":\"1\""),
                raw.substring(0,raw.length()-1)+",\"unexpected\":true}",raw.substring(0,raw.length()-1)+",\"format\":\"PROMPT_WORKSPACE_BINDINGS/v1\"}")){
            Files.writeString(originals.resolve("bad-bindings.json"),bad);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->new ProductPromptBindingRegistry(new ProductSourceEvidenceFiles(originals,1048576),
                    "bad-bindings.json",ProductComparisonJson.hash(bad))).isInstanceOf(IllegalStateException.class);
        }
    }
    private Receipt append(String promptId,long version,String body,boolean decimal){return append(promptId,version,body,decimal,900);}
    private Receipt append(String promptId,long version,String body,boolean decimal,long bodySeconds){
        var workspace=f.mainTx.execute(status->f.store.workspace(f.workspaceId,false));
        var variables=decimal?List.of(new VariableDefinition("amount",VariableDefinition.Type.DECIMAL,false,"project-input",true,
                new BigDecimal("12345678901234567890.01234567890123456789"),false)):List.<VariableDefinition>of();
        var ref=PromptRevision.referenceFor(promptId,id(),version,PromptRevision.Kind.SYSTEM,"Consultation draft",body,Set.of(),Set.of(),"internal","zh-CN",0,variables);
        var revision=new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Consultation draft",body,Set.of(),Set.of(),"internal","zh-CN",0,variables,
                new PromptRevision.Provenance("component-owned", "member",f.now,"member",f.now,"Owned component draft",List.of(),null,workspace.policyRef()));
        var write=new Write(workspace.id(),revision,version-1,version==1?Operation.CREATE:Operation.SAVE,ProductPromptWorkspaceAuthorizationFixture.ISSUER,
                "member",ProductComparisonJson.hash(id()),ProductComparisonJson.hash(body),f.now.plusSeconds(bodySeconds),f.now.plusSeconds(1600));
        PreparedWrite prepared=f.mainTx.execute(status->f.store.prepare(write));
        Head before=prepared.before(),after=prepared.after();
        var pending=new Pending(promptId,workspace.id(),revision.kind(),version-1,before==null?null:before.revisionId(),
                before==null?"0".repeat(64):before.scalarSha(),after.scalarSha(),ref.revisionId(),id(),write.operation(),write.keySha(),write.requestSha(),
                f.now.plusSeconds(500),after.containerUntil(),after.controlUntil());
        f.authorityTx.executeWithoutResult(status->f.control.reserve(pending));
        Receipt receipt=f.mainTx.execute(status->f.store.append(prepared));
        f.authorityTx.executeWithoutResult(status->f.control.complete(pending,prepared.after(),prepared.header(),receipt));
        return receipt;
    }
    private String base(){return "/v2/consultation/prompts/"+prompt+"/revisions";}
    private String exact(Receipt receipt){return base()+"/"+receipt.revisionId();}
    private MockHttpServletRequestBuilder auth(String path){return get(path).secure(true).header("Authorization","Bearer "+f.token);}
    private JsonNode perform(MockHttpServletRequestBuilder request,int status)throws Exception{
        var response=mvc.perform(request).andReturn().getResponse();String body=response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).withFailMessage(body).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return body.isBlank()?f.mapper.createObjectNode():f.mapper.readTree(body);
    }
    @org.springframework.boot.test.context.TestConfiguration @EnableWebMvc @EnableWebSecurity
    @Import({ProductPromptReadConfiguration.class,ProductPromptReadController.class,PromptRouteSecurityConfiguration.class,ExistingRouteSecurityConfiguration.class})
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
