package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.runtime.persistence.AgentCheckpointCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Production security chain/decoder/signing-key/session/Grant watermark and actual Controller/SQL Runtime.
 * Test-issued tokens, isolated H2, goal/workspace/search/provider boundaries are engineering fixtures:
 * not OAuth issuance, a TLS handshake, live Worker/provider/browser evidence or business acceptance. */
class ProductConsultationAgentSecurityTest {
    static final String ISSUER="https://consultation-security.invalid", AUDIENCE="https://api-security.invalid";
    AnnotationConfigWebApplicationContext context;
    Harness h;MockMvc mvc;
    @BeforeEach void setUp() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-settings",Map.of(
                "product.identity.enabled","true")));
        context.register(SecurityNode.class);context.refresh();h=context.getBean(Harness.class);
        mvc=MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ProductIdentityTransportFilter(h.watermark),context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close() { if(context!=null)context.close(); }
    String base(){return "/v2/consultation/tasks/"+h.f.taskId+"/agent-runs";}
    JsonNode postTo(String path,String token,boolean secure,int expected) throws Exception {
        var request=post(path).secure(secure).header("Idempotency-Key","secure-agent-key")
                .contentType("application/json").content("{\"expectedTaskVersion\":3}");
        if(token!=null)request.header("Authorization","Bearer "+token);
        var response=mvc.perform(request).andReturn().getResponse();assertEquals(expected,response.getStatus(),response.getContentAsString());
        return response.getContentAsString().isBlank()?h.f.json.createObjectNode():h.f.json.readTree(response.getContentAsString());
    }
    void noRunOrExternalCall(){assertEquals(0,h.f.db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));verifyNoInteractions(h.f.workflow,h.f.model);}
    @Test void anonymousMalformedAndWrongSignatureNeverReachRun() throws Exception {
        postTo(base(),null,true,401);postTo(base(),"not-a-jwt",true,401);
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        String[] parts=token.split("\\.");String signature=parts[2];
        postTo(base(),parts[0]+"."+parts[1]+"."+(signature.charAt(0)=='A'?'B':'A')+signature.substring(1),true,401);noRunOrExternalCall();
    }
    @Test void wrongIssuerAudienceAndExpiredTokensNeverReachRun() throws Exception {
        postTo(base(),h.token("component-actor","https://wrong-issuer.invalid",AUDIENCE,Instant.now().plusSeconds(120)),true,401);
        postTo(base(),h.token("component-actor",ISSUER,"https://wrong-audience.invalid",Instant.now().plusSeconds(120)),true,401);
        postTo(base(),h.token("component-actor",ISSUER,AUDIENCE,Instant.now().minusSeconds(120)),true,401);noRunOrExternalCall();
    }
    @Test void validSignedTokenCannotUsePlainHttpOrUnresolvedAuthority() throws Exception {
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        postTo(base(),token,false,403);
        h.authority.update("UPDATE id2_authority_watermark SET state='PENDING',intent_id='unknown' WHERE issuer=?",ISSUER);
        postTo(base(),token,true,503);noRunOrExternalCall();
    }
    @Test void authenticatedButUngrantedMemberCannotCreateRun() throws Exception {
        h.subject("ungranted-member");postTo(base(),h.token("ungranted-member",ISSUER,AUDIENCE,Instant.now().plusSeconds(120)),true,404);noRunOrExternalCall();
    }
    @Test void currentSessionRevocationBlocksExistingRunWithoutCheckpointMutation() throws Exception {
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        String run=postTo(base(),token,true,200).path("checkpoint").path("runId").asText();
        long version=h.f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class);
        h.f.db.update("UPDATE id2_session SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP");
        postTo(base()+"/"+run+"/advance",token,true,401);
        var read=mvc.perform(get(base()+"/"+run).secure(true).header("Authorization","Bearer "+token)).andReturn();
        assertEquals(401,read.getResponse().getStatus());assertEquals(version,h.f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));verifyNoInteractions(h.f.workflow,h.f.model);
    }
    @Test void currentWriteGrantRevocationBlocksResumeEvenWithValidSession() throws Exception {
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        String run=postTo(base(),token,true,200).path("checkpoint").path("runId").asText();
        long version=h.f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class);
        h.watermark.change(()->h.f.db.update("UPDATE id2_grant SET status='REVOKED',grant_version=grant_version+1 WHERE action_code='WRITE'"));
        postTo(base()+"/"+run+"/advance",token,true,404);
        assertEquals(version,h.f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));verifyNoInteractions(h.f.workflow,h.f.model);
    }
    @Test void disabledSubjectAndRevokedSigningKeyAreRejectedDespiteUnexpiredTokens() throws Exception {
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        h.f.db.update("UPDATE id2_subject SET status='DISABLED',credential_version=2,disabled_at=CURRENT_TIMESTAMP");
        postTo(base(),token,true,401);
        h.f.db.update("UPDATE id2_subject SET status='ACTIVE',disabled_at=NULL");
        h.keys.revoke(h.kid,"isolated-test-maintainer");postTo(base(),token,true,401);noRunOrExternalCall();
    }
    @Test void signedCurrentSessionAndTaskGrantsCompleteOnePersistedRun() throws Exception {
        String token=h.token("component-actor",ISSUER,AUDIENCE,Instant.now().plusSeconds(120));
        JsonNode result=postTo(base(),token,true,200);assertEquals(result,postTo(base(),token,true,200));
        String run=result.path("checkpoint").path("runId").asText();
        for(int i=0;i<20 && !"STOP".equals(result.path("checkpoint").path("phase").asText());i++)result=postTo(base()+"/"+run+"/advance",token,true,200);
        assertEquals("COMPLETED",result.path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(result,postTo(base()+"/"+run+"/advance",token,true,200));
        var read=mvc.perform(get(base()+"/"+run).secure(true).header("Authorization","Bearer "+token)).andReturn();
        assertEquals(200,read.getResponse().getStatus());assertEquals(result,h.f.json.readTree(read.getResponse().getContentAsString()));
        assertEquals(1,h.f.db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));
        assertEquals(1,h.f.db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));verify(h.f.model,times(1)).invoke(anyString(),anyString());
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({ProductIdentityRouteSecurityConfiguration.class,ExistingRouteSecurityConfiguration.class})
    static class SecurityNode {
        @Bean Harness harness(){return new Harness();}
        @Bean JwtDecoder jwtDecoder(Harness h,org.springframework.core.env.Environment env){
            return new ProductIssuerConfiguration(env).jwtDecoder(h.keys,h.f.db,h.watermark,ISSUER,AUDIENCE);
        }
        @Bean ProductConsultationAgentController controller(Harness h){return new ProductConsultationAgentController(
                h.f.workspace,h.f.workflow,h.f.dialogue,h.f.db,new DataSourceTransactionManager(h.f.db.getDataSource()),
                new AgentCheckpointCodec(h.f.json,Base64.getEncoder().encodeToString(new byte[32])),h.f.json);}
    }
    static class Harness {
        final ProductConsultationModelFixture f;final JdbcTemplate authority;
        final ProductGrantWatermark watermark;final ProductSigningKeys keys;final String kid;
        Harness(){
            var source=source();f=new ProductConsultationModelFixture(source,Instant.now());
            new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V032__product_identity.sql"),
                    new FileSystemResource("db/dm8/V096__agent_runtime_checkpoint.sql")).execute(source);
            var independent=source();new ResourceDatabasePopulator(new FileSystemResource("db/identity-authority/V001__grant_watermark.sql")).execute(independent);
            authority=new JdbcTemplate(independent);
            authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,ProductGrantHistoryBatch.digest(f.db,ISSUER,false,false));
            watermark=new ProductGrantWatermark(f.db,authority,ISSUER);subject("component-actor");
            keys=new ProductSigningKeys(f.db,new TransactionTemplate(new DataSourceTransactionManager(source)),ISSUER,
                    Base64.getEncoder().encodeToString(new byte[32]),Duration.ofMinutes(5),Clock.systemUTC());
            kid=keys.rotate("isolated-test-maintainer");
            watermark.change(()->{for(String action:List.of("READ","WRITE")){
                String grant=UUID.randomUUID().toString();Instant now=Instant.now();
                f.db.update("INSERT INTO id2_grant VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",grant,ISSUER,"component-actor","org","CONSULTATION",f.taskId,"scope:1","TASK",f.taskId,1,action,"TASK_WORK",1,Timestamp.from(now),Timestamp.from(now.plusSeconds(3600)),"ACTIVE",1);
                f.db.update("INSERT INTO id2_current_epoch VALUES(?,?,?,?,?,?,?,?,?,?,?)",ISSUER,"component-actor","org","CONSULTATION","TASK",f.taskId,action,"TASK_WORK",1,1,grant);
            }return null;});
            var access=new ProductConsultationAccess(f.db,watermark,ISSUER,Clock.systemUTC());
            var view=f.workspace.read(f.taskId,f.jwt);var task=view.task();var goal=view.goal();var page=view.result();
            doAnswer(a->access.permits(a.getArgument(0),a.getArgument(1),a.getArgument(2))).when(f.access).permits(any(Jwt.class),any(),any());
            doAnswer(a->{Jwt jwt=a.getArgument(1);if(!access.permits(jwt,new ProductConsultationAccess.ResourceFacts("org",f.taskId,"scope:1","TASK",f.taskId,1),ProductAccessPolicy.Action.READ))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);return view;}).when(f.workspace).read(eq(f.taskId),any(Jwt.class));
            doAnswer(a->{Jwt jwt=a.getArgument(1);if(!access.permits(jwt,new ProductConsultationAccess.ResourceFacts("org",f.taskId,"scope:1","TASK",f.taskId,1),a.getArgument(2)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);return task;}).when(f.workspace).permitted(eq(f.taskId),any(Jwt.class),any());
            when(f.goals.current(eq(f.taskId),any(Jwt.class))).thenReturn(goal);
            when(f.searches.read(eq(f.taskId),eq(f.resultId),eq(20),isNull(),any(Jwt.class))).thenReturn(page);
            when(f.workflow.execute(eq(f.taskId),eq(3L),any(Jwt.class))).thenReturn(page);
        }
        static JdbcDataSource source(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");return source;}
        void subject(String subject){f.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,'fixture-only-not-login','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER,subject,subject);}
        String token(String subject,String issuer,String audience,Instant expiry){
            Instant now=Instant.now();String sid=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString();
            f.db.update("INSERT INTO id2_session VALUES(?,?,?,?,?,?,?,?,'ACTIVE',NULL)",sid,jti,ISSUER,subject,"isolated-client",kid,Timestamp.from(now.minusSeconds(300)),Timestamp.from(now.plusSeconds(3600)));
            var claims=JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(List.of(audience)).id(jti)
                    .claim("sid",sid).issuedAt(now.minusSeconds(300)).expiresAt(expiry).build();
            return new NimbusJwtEncoder(keys).encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).keyId(kid).build(),claims)).getTokenValue();
        }
    }
}
