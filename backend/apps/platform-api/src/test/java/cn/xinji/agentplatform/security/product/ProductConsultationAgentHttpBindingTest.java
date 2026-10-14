package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.runtime.persistence.AgentCheckpointCodec;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.*;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real HTTP dispatch/controller/runtime/SQL. JWT resolver and domain boundary ports are test fixtures,
 * not proof of production token authentication, real worker transport or professional quality. */
class ProductConsultationAgentHttpBindingTest {
    final ProductConsultationModelFixture f;
    ProductConsultationAgentHttpBindingTest() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        f=new ProductConsultationModelFixture(source,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V096__agent_runtime_checkpoint.sql")).execute(source);
    }
    String base() { return "/v2/consultation/tasks/"+f.taskId+"/agent-runs"; }
    final org.springframework.security.oauth2.jwt.JwtDecoder decoder=mock(org.springframework.security.oauth2.jwt.JwtDecoder.class);
    cn.xinji.agentplatform.runtime.AuthorizedAgentExecution execution() {
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("decoder",decoder);
        when(decoder.decode(anyString())).thenReturn(f.jwt);
        return new cn.xinji.agentplatform.runtime.AuthorizedAgentExecution(beans.getBeanProvider(org.springframework.security.oauth2.jwt.JwtDecoder.class));
    }
    org.springframework.test.web.servlet.MockMvc mvc() {
        var controller=new ProductConsultationAgentController(f.workspace,f.workflow,f.dialogue,f.db,
                new DataSourceTransactionManager(f.db.getDataSource()),new AgentCheckpointCodec(f.json,Base64.getEncoder().encodeToString(new byte[32])),f.json,execution());
        return MockMvcBuilders.standaloneSetup(controller).setMessageConverters(new MappingJackson2HttpMessageConverter(f.json))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.getParameterType()==Jwt.class; }
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest w,WebDataBinderFactory b) { return f.jwt; }
                }).build();
    }
    JsonNode request(String path, String body, String key,int status) throws Exception {
        var builder=post(path).contentType("application/json").content(body);
        if(key!=null)builder.header("Idempotency-Key",key);
        var result=mvc().perform(builder).andReturn();assertEquals(status,result.getResponse().getStatus());
        String raw=result.getResponse().getContentAsString();return raw.isBlank()?f.json.createObjectNode():f.json.readTree(raw);
    }
    JsonNode start() throws Exception { return request(base(),"{\"expectedTaskVersion\":3}","http-key",200); }
    @Test void oneExecuteRequestCompletesPersistedRunAndRepeatedRequestDoesNotReplayTools() throws Exception {
        var created=start();String run=created.path("checkpoint").path("runId").asText();
        var result=request(base()+"/"+run+"/execute","{}",null,200);
        assertEquals("COMPLETED",result.path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(result,request(base()+"/"+run+"/execute","{}",null,200));
        verify(decoder,atLeast(3)).decode(f.jwt.getTokenValue());
    }
    @Test void strictStartInputRejectsUnknownFieldsMissingKeyFractionAndStaleVersionBeforeCreatingRun() throws Exception {
        request(base(),"{\"expectedTaskVersion\":3,\"modelId\":\"injected\"}","key",400);
        request(base(),"{\"expectedTaskVersion\":3}",null,400);
        request(base(),"{\"expectedTaskVersion\":3.5}","key",400);
        request(base(),"{\"expectedTaskVersion\":2}","key",409);
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));verifyNoInteractions(f.model);
    }
    @Test void sameKeyStartAndNewControllerPerStepCompleteOnePersistedRunAndAnswer() throws Exception {
        var created=start();String run=created.path("checkpoint").path("runId").asText();assertFalse(run.isBlank());
        assertEquals(created,start());JsonNode result=created;
        for(int i=0;i<20 && !"STOP".equals(result.path("checkpoint").path("phase").asText());i++)
            result=request(base()+"/"+run+"/advance","{}",null,200);
        assertEquals("COMPLETED",result.path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(result,request(base()+"/"+run+"/advance","{}",null,200));
        var fetched=mvc().perform(get(base()+"/"+run)).andReturn();assertEquals(200,fetched.getResponse().getStatus());
        assertEquals(result,f.json.readTree(fetched.getResponse().getContentAsString()));
        assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));
        assertEquals(1,f.db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));verify(f.model,times(1)).invoke(anyString(),anyString());
    }
    @Test void currentPermissionDenialBlocksAdvanceWithoutMutatingRun() throws Exception {
        var run=start().path("checkpoint").path("runId").asText();long version=f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class);
        when(f.workspace.read(f.taskId,f.jwt)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        request(base()+"/"+run+"/advance","{}",null,404);
        assertEquals(version,f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));verifyNoInteractions(f.model);
    }
    @Test void changedGoalCannotResumeOldRunAsNewTaskGoal() throws Exception {
        var run=start().path("checkpoint").path("runId").asText();var view=f.workspace.read(f.taskId,f.jwt);var g=view.goal();
        var changed=new ProductTaskGoalReader.GoalView(g.taskId(),g.taskVersion(),"different-goal",g.goalRevision(),g.inputId(),g.purpose(),g.messageText(),g.queryObject(),g.sourceIds(),g.requestedFields(),g.requiredFeatures(),g.confirmedAt(),g.expiresAt());
        when(f.workspace.read(f.taskId,f.jwt)).thenReturn(new ProductRetrievalWorkspace.View(view.task(),changed,view.sources(),view.result(),view.retentionRef(),view.retentionVersion(),view.selectionRevision(),view.dialogue()));
        request(base()+"/"+run+"/advance","{}",null,403);assertEquals(0,f.db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));verifyNoInteractions(f.model);
    }
    @Test void unknownProviderResultBecomesErrorNotCompletedOrAutomaticReplan() throws Exception {
        when(f.model.invoke(anyString(),anyString())).thenThrow(new IllegalStateException("provider outcome unknown"));
        var result=start();String run=result.path("checkpoint").path("runId").asText();
        for(int i=0;i<20 && !"STOP".equals(result.path("checkpoint").path("phase").asText());i++) result=request(base()+"/"+run+"/advance","{}",null,200);
        assertEquals("ERROR",result.path("checkpoint").path("outcome").path("reason").asText());
        assertEquals(0,result.path("checkpoint").path("replans").asInt());assertEquals(result,request(base()+"/"+run+"/advance","{}",null,200));
        assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));verify(f.model,times(1)).invoke(anyString(),anyString());
    }
}
