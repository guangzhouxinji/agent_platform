package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.agent.ConsultationAgent;
import cn.xinji.agentplatform.consultation.application.ConsultationRetrievalModelPort;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual dialogue validation, transaction and SQL retention; external ports are component fixtures, not business Golden. */
class ProductConsultationModelFixture {
    final Instant now;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final String taskId = UUID.randomUUID().toString(), resultId = UUID.randomUUID().toString();
    final String attemptId = UUID.randomUUID().toString(), inputId = UUID.randomUUID().toString();
    final Jwt jwt = Jwt.withTokenValue("component-only").header("alg","RS256").subject("component-actor").build();
    final ProductRetrievalWorkflow workflow = mock(ProductRetrievalWorkflow.class);
    final ProductRetrievalWorkspace workspace = mock(ProductRetrievalWorkspace.class);
    final ProductTaskDirectory tasks = mock(ProductTaskDirectory.class);
    final ProductConsultationAccess access = mock(ProductConsultationAccess.class);
    final ProductTaskGoalReader goals = mock(ProductTaskGoalReader.class);
    final ProductPatentSearchExecution searches = mock(ProductPatentSearchExecution.class);
    final ProductSourceAvailability sources = mock(ProductSourceAvailability.class);
    final ConsultationRetrievalModelPort model = mock(ConsultationRetrievalModelPort.class);
    final JdbcTemplate db;
    final ProductRetrievalDialogue dialogue;
    final ProductConsultationAgentTools tools;
    final Checkpoint checkpoint;
    final Invocation invocation = new Invocation("run/1", new Step("draft","ConsultationAgent","answer-draft","task"));

    ProductConsultationModelFixture(javax.sql.DataSource source) { this(source,Instant.parse("2026-10-06T00:00:00Z")); }
    ProductConsultationModelFixture(javax.sql.DataSource source,Instant now) {
        this.now=now;
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY)");
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V089__product_consultation_model_turn.sql")).execute(source);
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V097__consultation_model_invocation.sql")).execute(source);
        db.execute("CREATE TABLE con4_search_attempt(attempt_id VARCHAR(36),task_id VARCHAR(36),input_revision VARCHAR(36))");
        db.execute("CREATE TABLE con4_result_set(result_set_id VARCHAR(36),attempt_id VARCHAR(36))");
        db.execute("CREATE TABLE con3_search_input(input_id VARCHAR(36),task_id VARCHAR(36),requested_fields_json VARCHAR(100))");
        db.update("INSERT INTO con3_task VALUES(?)",taskId);
        db.update("INSERT INTO con4_search_attempt VALUES(?,?,?)",attemptId,taskId,inputId);
        db.update("INSERT INTO con4_result_set VALUES(?,?)",resultId,attemptId);
        db.update("INSERT INTO con3_search_input VALUES(?,?,?)",inputId,taskId,"[\"title\"]");
        var task = new ProductTaskDirectory.Task(taskId,"org","issuer","component-actor","ACTIVE",3,1,"scope",1,now.plusSeconds(3600));
        var goal = new ProductTaskGoalReader.GoalView(taskId,3,"goal",2,inputId,"QUERY","PRIVATE_RAW_TASK_TEXT","confirmed query",List.of(),List.of("title"),List.of(),now,task.expiresAt());
        var page = new ProductPatentSearchExecution.Page(resultId,attemptId,"method","a".repeat(64),false,0,20,null,"LOW","rule","no matches",List.of(),"COMPLETE","EXACT",List.of(),List.of(),"digest-v1");
        when(tasks.find(taskId)).thenReturn(Optional.of(task));
        when(access.permits(eq(jwt),any(),any())).thenReturn(true);
        when(goals.current(taskId,jwt)).thenReturn(goal);
        when(searches.read(taskId,resultId,20,null,jwt)).thenReturn(page);
        when(workflow.execute(taskId,3,jwt)).thenReturn(page);
        when(workspace.read(taskId,jwt)).thenReturn(new ProductRetrievalWorkspace.View(task,goal,List.of(),page,"component-policy",1,0,List.of()));
        when(model.invoke(anyString(),anyString())).thenReturn(new ConsultationRetrievalModelPort.Invocation("component-model","RETRIEVAL_DIALOGUE/v3","{\"answer\":\"No matching records in this component input\",\"citations\":[]}","component envelope"));
        dialogue = new ProductRetrievalDialogue(db,tasks,access,goals,searches,sources,model,new TransactionTemplate(new DataSourceTransactionManager(source)),new ProductRetentionPolicy("component-policy",1,"b".repeat(64),Duration.ofHours(1)),json,Clock.fixed(now,ZoneOffset.UTC));
        tools = new ProductConsultationAgentTools(workspace,workflow,dialogue,jwt,json);
        checkpoint = new Checkpoint("run",ConsultationAgent.DEFINITION,new Task(taskId,ConsultationAgent.TASK_TYPE,task.dataScope(),new Goal("goal",ProductConsultationAgentTools.version(goal),"goal",ConsultationAgent.COMPLETION_CONTRACT)),new Budget(12,2,Duration.ofMinutes(3)),now,0,Phase.OBSERVE,null,0,0,0,List.of(),null,null);
    }
    @Test void actualExplanationPersistsAndReplaysWithoutSendingRawTaskText() throws Exception {
        var first = tools.draft(checkpoint,invocation);
        assertEquals(first,tools.draft(checkpoint,invocation));
        assertTrue(first.reference().startsWith("answer-turn:"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        var request = json.readTree(db.queryForObject("SELECT request_json FROM con3_model_turn",String.class));
        assertEquals("confirmed query",request.path("goal").asText());
        assertFalse(request.toString().contains("PRIVATE_RAW_TASK_TEXT"));
        verify(model,times(1)).invoke(anyString(),anyString());
        var turn = dialogue.read(taskId,first.reference().substring("answer-turn:".length()),jwt);
        assertEquals(resultId,turn.resultSetId()); assertEquals("a".repeat(64),turn.resultSha());
    }
    @Test void dialogueStillRejectsRawTextInsteadOfLooseningBoundary() {
        status(400,()->dialogue.invoke(taskId,jwt,"request","EXPLANATION",new ProductRetrievalDialogue.Input(3,"raw text",resultId)));
        verifyNoInteractions(model);
    }
    @Test void revokedCurrentAccessNeverCallsModelOrPersistsAnswer() {
        when(access.permits(eq(jwt),any(),any())).thenReturn(false);
        status(404,()->tools.draft(checkpoint,invocation)); verifyNoInteractions(model);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
    }
    @Test void staleResultInputCannotBeExplained() {
        db.update("UPDATE con4_search_attempt SET input_revision=?",UUID.randomUUID().toString());
        status(409,()->tools.draft(checkpoint,invocation)); verifyNoInteractions(model);
    }
    @Test void staleTaskVersionIsRejectedBeforeModelCall() {
        status(409,()->dialogue.invoke(taskId,jwt,"request","EXPLANATION",new ProductRetrievalDialogue.Input(2,null,resultId)));
        verifyNoInteractions(model);
    }
    @Test void invalidModelAnswerIsRetainedAsFailureAndNeverReplayedAsSuccess() {
        when(model.invoke(anyString(),anyString())).thenReturn(new ConsultationRetrievalModelPort.Invocation("component-model","RETRIEVAL_DIALOGUE/v3","{\"answer\":\"unsupported\",\"citations\":[\"foreign-member\"]}","invalid output"));
        status(502,()->tools.draft(checkpoint,invocation));
        status(502,()->tools.draft(checkpoint,invocation));
        verify(model,times(1)).invoke(anyString(),anyString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
    }
    @Test void realRuntimeAndSqlCheckpointResumeIntoPersistedAnswerAndEvaluation() {
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V096__agent_runtime_checkpoint.sql")).execute(db.getDataSource());
        var clock = Clock.fixed(now,ZoneOffset.UTC);
        var codec = new AgentCheckpointCodec(json,Base64.getEncoder().encodeToString(new byte[32]));
        var binding = new JdbcAgentCheckpointStore.Binding(ConsultationAgent.DEFINITION.agentId(),taskId,
                checkpoint.task().goal().id(),checkpoint.task().goal().version(),checkpoint.task().scope(),now.plusSeconds(3600));
        var store = new JdbcAgentCheckpointStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),codec,binding,
                (b,c)->tools.current(c == null ? checkpoint : c),clock);
        var agent = new ConsultationAgent(store,tools,(c,step)->{tools.current(c);return true;},clock);
        agent.start("run",checkpoint.task(),checkpoint.budget());
        Checkpoint current = store.load("run");
        for(int i=0;i<20 && current.phase()!=Phase.STOP;i++) {
            // New adapters on every step: no reliance on an in-memory runtime to finish the task.
            var recoveredStore = new JdbcAgentCheckpointStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),codec,binding,(b,c)->tools.current(c == null ? checkpoint : c),clock);
            agent = new ConsultationAgent(recoveredStore,tools,(c,step)->{tools.current(c);return true;},clock);
            current = agent.advance("run");
        }
        assertEquals(Phase.STOP,current.phase()); assertEquals(StopReason.COMPLETED,current.outcome().reason());
        assertEquals(3,current.invocations()); assertEquals(0,current.replans());
        assertTrue(current.outcome().reference().startsWith("answer-turn:"));
        assertEquals(current,agent.advance("run"));
        assertEquals(current.version()+1,store.events("run").size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        verify(workflow,times(1)).execute(taskId,3,jwt); verify(model,times(1)).invoke(anyString(),anyString());
    }
    @Test void unknownProviderOutcomeIsNotDispatchedAgainAfterServiceRestart() {
        when(model.invoke(anyString(),anyString())).thenThrow(new IllegalStateException("provider outcome unknown"));
        assertThrows(IllegalStateException.class,()->tools.draft(checkpoint,invocation));
        var restarted = new ProductRetrievalDialogue(db,tasks,access,goals,searches,sources,model,
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
                new ProductRetentionPolicy("component-policy",1,"b".repeat(64),Duration.ofHours(1)),json,Clock.fixed(now,ZoneOffset.UTC));
        String key=UUID.nameUUIDFromBytes(invocation.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        status(409,()->restarted.invoke(taskId,jwt,key,"EXPLANATION",new ProductRetrievalDialogue.Input(3,null,resultId)));
        verify(model,times(1)).invoke(anyString(),anyString());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
    }
    @Test void successfulExternalCallThenFailedPersistenceMustNotCallProviderAgain() {
        db.execute("ALTER TABLE con3_model_turn ADD CONSTRAINT reject_fixture_turn CHECK (turn_id='never')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tools.draft(checkpoint,invocation));
        status(409,()->tools.draft(checkpoint,invocation));
        verify(model,times(1)).invoke(anyString(),anyString());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
    }
    @Test void concurrentSameKeyIsRejectedBeforeSecondModelDispatch() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
        var response=new ConsultationRetrievalModelPort.Invocation("component-model","RETRIEVAL_DIALOGUE/v3",
                "{\"answer\":\"No matching records\",\"citations\":[]}","component envelope");
        when(model.invoke(anyString(),anyString())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown(); assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS)); return response;
        });
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->tools.draft(checkpoint,invocation));
            try { assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)); status(409,()->tools.draft(checkpoint,invocation)); }
            finally { release.countDown(); }
            var result=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(result,tools.draft(checkpoint,invocation));
        }
        verify(model,times(1)).invoke(anyString(),anyString());
    }
    @Test void proposalUnknownOutcomeAlsoRejectsSameKeyAndChangedInput() {
        when(model.invoke(anyString(),anyString())).thenThrow(new IllegalStateException("unknown"));
        assertThrows(IllegalStateException.class,()->dialogue.invoke(taskId,jwt,"proposal-key","PROPOSAL",new ProductRetrievalDialogue.Input(3,"public goal",null)));
        status(409,()->dialogue.invoke(taskId,jwt,"proposal-key","PROPOSAL",new ProductRetrievalDialogue.Input(3,"public goal",null)));
        status(409,()->dialogue.invoke(taskId,jwt,"proposal-key","PROPOSAL",new ProductRetrievalDialogue.Input(3,"different goal",null)));
        verify(model,times(1)).invoke(anyString(),anyString());
    }
    @Test void authorityRevokedDuringExternalCallCannotPersistOrReplayAnswer() {
        when(model.invoke(anyString(),anyString())).thenAnswer(call->{
            when(access.permits(eq(jwt),any(),any())).thenReturn(false);
            return new ConsultationRetrievalModelPort.Invocation("component-model","RETRIEVAL_DIALOGUE/v3","{\"answer\":\"No matches\",\"citations\":[]}","component envelope");
        });
        status(404,()->tools.draft(checkpoint,invocation));
        when(access.permits(eq(jwt),any(),any())).thenReturn(true);
        status(409,()->tools.draft(checkpoint,invocation));
        verify(model,times(1)).invoke(anyString(),anyString());
    }
    @Test void savedAnswerThenCheckpointFailureRecoversSameTurnWithoutSecondProviderCall() {
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V096__agent_runtime_checkpoint.sql")).execute(db.getDataSource());
        var store=runtimeStore();var failed=new java.util.concurrent.atomic.AtomicBoolean();
        cn.xinji.agentplatform.runtime.AgentPorts.CheckpointStore failOnce=new cn.xinji.agentplatform.runtime.AgentPorts.CheckpointStore() {
            public Checkpoint load(String run) { return store.load(run); }
            public void commit(long version,Checkpoint next,cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent event) {
                if(next.phase()==Phase.EVALUATE && next.observations().getLast().reference().startsWith("answer-turn:") && !failed.getAndSet(true))
                    throw new IllegalStateException("component checkpoint write failure");
                store.commit(version,next,event);
            }
        };
        var agent=new ConsultationAgent(failOnce,tools,(c,step)->{tools.current(c);return true;},Clock.fixed(now,ZoneOffset.UTC));
        agent.start("run",checkpoint.task(),checkpoint.budget());
        assertThrows(IllegalStateException.class,()->{for(int i=0;i<20;i++)agent.advance("run");});
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
        assertNotNull(store.load("run").pending());
        var recovered=new ConsultationAgent(runtimeStore(),tools,(c,step)->{tools.current(c);return true;},Clock.fixed(now,ZoneOffset.UTC));
        Checkpoint result=store.load("run");for(int i=0;i<20 && result.phase()!=Phase.STOP;i++)result=recovered.advance("run");
        assertEquals(StopReason.COMPLETED,result.outcome().reason());assertEquals(result,recovered.advance("run"));
        assertEquals(result.version()+1,store.events("run").size());verify(model,times(1)).invoke(anyString(),anyString());
    }
    @Test void unknownModelOutcomeStopsRuntimeWithoutReplanOrSuccess() {
        new ResourceDatabasePopulator(new FileSystemResource("db/dm8/V096__agent_runtime_checkpoint.sql")).execute(db.getDataSource());
        when(model.invoke(anyString(),anyString())).thenThrow(new IllegalStateException("unknown provider outcome"));
        var agent=new ConsultationAgent(runtimeStore(),tools,(c,step)->{tools.current(c);return true;},Clock.fixed(now,ZoneOffset.UTC));
        Checkpoint result=agent.start("run",checkpoint.task(),checkpoint.budget());
        for(int i=0;i<20 && result.phase()!=Phase.STOP;i++)result=agent.advance("run");
        assertEquals(StopReason.ERROR,result.outcome().reason());assertEquals(0,result.replans());assertEquals(result,agent.advance("run"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM con3_model_turn",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));verify(model,times(1)).invoke(anyString(),anyString());
    }
    private JdbcAgentCheckpointStore runtimeStore() {
        var binding=new JdbcAgentCheckpointStore.Binding(ConsultationAgent.DEFINITION.agentId(),taskId,
                checkpoint.task().goal().id(),checkpoint.task().goal().version(),checkpoint.task().scope(),now.plusSeconds(3600));
        return new JdbcAgentCheckpointStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
                new AgentCheckpointCodec(json,Base64.getEncoder().encodeToString(new byte[32])),binding,
                (b,c)->tools.current(c==null?checkpoint:c),Clock.fixed(now,ZoneOffset.UTC));
    }
    @Test void nanosecondClockFirstResponseMatchesPersistedReplayEvidence() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        var precise=new ProductConsultationModelFixture(source,Instant.parse("2026-10-06T00:00:00.123456789Z"));
        precise.actualExplanationPersistsAndReplaysWithoutSendingRawTaskText();
    }
    private void status(int code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code,assertThrows(ResponseStatusException.class,call).getStatusCode().value());
    }
}
