package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.agent.ConsultationAgent;
import cn.xinji.agentplatform.consultation.application.ConsultationRetrievalModelPort;
import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.AgentEvent;
import cn.xinji.agentplatform.runtime.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only self-owned file-H2 rows. Actual product readers, authorization, isolation and encrypted SQL
 * checkpoints; Source/content ports are engineering doubles, NOT source-rights/original validation.
 * Reuses the existing private JDBC fixture reflectively without editing it or any historical bytes. */
class ProductHistoricalIndirectConsumptionTest {
    @TempDir Path temporary;
    static final Instant NOW=Instant.parse("2026-10-01T00:00:00Z");
    static final String ISSUER="https://own-indirect.invalid", POLICY="own-indirect-policy";
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    Harness h;
    @AfterEach void evidence(TestInfo info) throws Exception {
        if(h==null)return;
        String after=h.snapshot();String name=info.getTestMethod().orElseThrow().getName();
        Path dir=System.getProperty("isolation.indirect.evidence.dir")==null?temporary:
                Path.of(System.getProperty("isolation.indirect.evidence.dir"));
        Files.createDirectories(dir);Files.writeString(dir.resolve(name+"-before.json"),h.before);
        Files.writeString(dir.resolve(name+"-after.json"),after);
        Files.writeString(dir.resolve(name+"-receipt.json"),JSON.writeValueAsString(Map.of(
                "engine","ACTUAL_FILE_H2_PRODUCT_JDBC","engineeringOwnSample",true,"realHistoricalSamples",0,
                "beforeSha",digest(h.before),"afterSha",digest(after),"allOwnRowsUnchanged",h.before.equals(after),
                "originalResultSha",h.resultSha,"storedResultSha",h.db.queryForObject(
                        "SELECT result_sha FROM con4_result_set WHERE result_set_id=?",String.class,h.result))));
        h.db.execute("SHUTDOWN");h.authority.execute("SHUTDOWN");
    }
    Harness fixture(boolean closed) throws Exception {h=new Harness(temporary,closed);return h;}
    static String digest(String value){return ProductVectorCodec.sha(value.getBytes(StandardCharsets.UTF_8));}
    void rejected(org.junit.jupiter.api.function.Executable call) {
        var error=assertThrows(ProductHistoricalResultIsolation.Unverified.class,call);
        assertEquals(ProductHistoricalResultIsolation.CODE,error.getReason());assertEquals(h.before,h.snapshot());
        assertEquals(h.resultSha,h.db.queryForObject("SELECT result_sha FROM con4_result_set WHERE result_set_id=?",String.class,h.result));
        verifyNoInteractions(h.model);assertEquals(0,h.db.queryForObject("SELECT COUNT(*) FROM con3_model_invocation",Integer.class));
    }
    static void status(int code,org.junit.jupiter.api.function.Executable call) {
        assertEquals(code,assertThrows(ResponseStatusException.class,call).getStatusCode().value());
    }
    @Test void retainedExplanationReadRejectsMissingBuildWithoutChangingSha() throws Exception {
        var f=fixture(false);rejected(()->f.dialogue.read(f.task,f.turn,f.jwt));
    }
    @Test void currentResultExplanationRejectsBeforeFenceOrExternalModel() throws Exception {
        var f=fixture(false);rejected(()->f.dialogue.invoke(f.task,f.jwt,"new-own-explanation","EXPLANATION",
                new ProductRetrievalDialogue.Input(1,null,f.result)));
    }
    @Test void explanationIdempotentReplayRechecksDependency() throws Exception {
        var f=fixture(false);String request=JSON.writeValueAsString(JSON.createObjectNode().put("kind","EXPLANATION")
                .set("input",JSON.valueToTree(new ProductRetrievalDialogue.Input(1,null,f.result))));
        f.db.update("UPDATE con3_model_turn SET request_sha=? WHERE turn_id=?",digest(request),f.turn);f.freeze();
        rejected(()->f.dialogue.invoke(f.task,f.jwt,"own-turn","EXPLANATION",new ProductRetrievalDialogue.Input(1,null,f.result)));
    }
    @Test void workspaceCurrentResultRejectsMissingBuild() throws Exception {
        var f=fixture(false);rejected(()->f.workspace.read(f.task,f.jwt));
    }
    @Test void workspaceRetainedTurnRejectsEvenWithoutCurrentGoalResult() throws Exception {
        var f=fixture(false);f.db.update("UPDATE con3_task SET state='ACTIVE'");f.freeze();
        rejected(()->f.workspace.read(f.task,f.jwt));
    }
    @Test void nullIdentityPrecedesHistoricalDependencyRead() throws Exception {
        var f=fixture(false);status(401,()->f.dialogue.read(f.task,f.turn,null));
        status(401,()->f.workspace.read(f.task,null));status(401,()->f.dialogue.invoke(f.task,null,"new","EXPLANATION",
                new ProductRetrievalDialogue.Input(1,null,f.result)));assertEquals(f.before,f.snapshot());verifyNoInteractions(f.sources,f.model);
    }
    @Test void revokedCurrentReadGrantPrecedesAllIndirectReads() throws Exception {
        var f=fixture(false);f.revoke("READ");f.freeze();status(404,()->f.dialogue.read(f.task,f.turn,f.jwt));
        status(404,()->f.workspace.read(f.task,f.jwt));status(404,()->f.dialogue.invoke(f.task,f.jwt,"new","EXPLANATION",
                new ProductRetrievalDialogue.Input(1,null,f.result)));assertEquals(f.before,f.snapshot());verifyNoInteractions(f.sources,f.model);
    }
    @Test void revokedWritePrecedesCurrentResultExplanation() throws Exception {
        var f=fixture(false);f.revoke("WRITE");f.freeze();status(404,()->f.dialogue.invoke(f.task,f.jwt,"new","EXPLANATION",
                new ProductRetrievalDialogue.Input(1,null,f.result)));assertEquals(f.before,f.snapshot());verifyNoInteractions(f.sources,f.model);
    }
    @Test void currentSourceDenialPrecedesMissingHistoricalWitness() throws Exception {
        var f=fixture(false);when(f.sources.require("OWN_SOURCE","CLAIMS","QUERY"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"OWN_SOURCE_DENIED"));
        status(503,()->f.dialogue.read(f.task,f.turn,f.jwt));status(503,()->f.workspace.read(f.task,f.jwt));
        assertEquals(f.before,f.snapshot());verifyNoInteractions(f.model);
    }
    @Test void corruptStoredResultDigestIsHardFailureNotMissingWitness() throws Exception {
        var f=fixture(false);f.db.update("UPDATE con4_result_set SET result_sha=?","f".repeat(64));f.freeze();
        assertThrows(IllegalStateException.class,()->f.dialogue.read(f.task,f.turn,f.jwt));assertEquals(f.before,f.snapshot());
    }
    @Test void independentlyClosedResultRemainsUsableInDialogueAndWorkspace() throws Exception {
        var f=fixture(true);assertEquals(f.resultSha,f.dialogue.read(f.task,f.turn,f.jwt).resultSha());
        assertEquals(f.result,f.workspace.read(f.task,f.jwt).result().resultSetId());assertEquals(f.before,f.snapshot());
        verifyNoInteractions(f.model);
    }
    @Test void unrelatedPublicProposalRemainsReadableBesideIsolatedResult() throws Exception {
        var f=fixture(false);String proposal=f.addTurn(null,null,"PROPOSAL",NOW.plusSeconds(1));f.freeze();
        assertEquals("PROPOSAL",f.dialogue.read(f.task,proposal,f.jwt).kind());assertEquals(f.before,f.snapshot());verifyNoInteractions(f.sources,f.model);
    }
    @Test void restartedSqlRuntimeRejectsCurrentMissingResultBeforeCheckpointAdvance() throws Exception {
        var f=fixture(true);String run=f.persist(false);f.db.update("DELETE FROM con4_vector_build");f.freeze();
        rejected(()->f.restart().advance(run));
    }
    @Test void restartedSqlRuntimeRevokedGrantPrecedesMissingResult() throws Exception {
        var f=fixture(true);String run=f.persist(false);f.db.update("DELETE FROM con4_vector_build");f.revoke("READ");f.freeze();
        status(404,()->f.restart().advance(run));assertEquals(f.before,f.snapshot());verifyNoInteractions(f.model);
    }
    @Test void restartedRuntimeLocatableOldAnswerBeyondWorkspaceWindowMustRejectBeforeAdvance() throws Exception {
        var f=fixture(true);String run=f.persist(true);String healthy=f.installNewerResult();
        for(int i=1;i<=3;i++)f.addTurn(null,null,"PROPOSAL",NOW.plusSeconds(i));f.freeze();
        assertEquals(healthy,f.workspace.read(f.task,f.jwt).result().resultSetId());
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,()->f.dialogue.read(f.task,f.turn,f.jwt));
        // Reject on load before any Run revision is written, stronger than the old terminal-code assertion.
        rejected(()->f.restart().advance(run));
    }
    @Test void restartedRuntimeLocatableRetrievalMustRejectWithHealthyCurrentResult() throws Exception {locatableRetrieval("retrieval:");}
    @Test void restartedRuntimeLocatableCitationsMustRejectWithHealthyCurrentResult() throws Exception {locatableRetrieval("retrieval-citations:");}
    void locatableRetrieval(String prefix) throws Exception {
        var f=fixture(true);String run=f.persistRetrieval(prefix);String healthy=f.installNewerResult();
        for(int i=1;i<=3;i++)f.addTurn(null,null,"PROPOSAL",NOW.plusSeconds(i));f.freeze();
        assertEquals(healthy,f.workspace.read(f.task,f.jwt).result().resultSetId());
        assertThrows(ProductHistoricalResultIsolation.Unverified.class,()->f.dialogue.read(f.task,f.turn,f.jwt));
        rejected(()->f.restart().advance(run));
    }
    @Test void readCheckpointClosedDependencyRemainsUsableWithoutWriteGrant() throws Exception {
        var f=fixture(true);String run=f.persistRetrieval("retrieval-citations:");f.revoke("WRITE");f.freeze();
        status(404,()->f.tools.current(f.seed)); // Control: WRITE really is absent, not an all-allow port.
        var loaded=f.readOnlyStore().load(run);assertEquals(Phase.PLAN,loaded.phase());assertEquals(1,loaded.version());
        assertEquals("retrieval-citations:"+f.result,loaded.observations().getFirst().reference());assertEquals(f.before,f.snapshot());verifyNoInteractions(f.model);
    }
    @Test void readCheckpointMissingDependencyReturnsIsolationCodeNotMissingWriteGrant() throws Exception {
        var f=fixture(true);String run=f.persistRetrieval("retrieval:");String healthy=f.installNewerResult();
        for(int i=1;i<=3;i++)f.addTurn(null,null,"PROPOSAL",NOW.plusSeconds(i));f.revoke("WRITE");f.freeze();
        assertEquals(healthy,f.workspace.read(f.task,f.jwt).result().resultSetId());
        status(404,()->f.tools.current(f.seed));rejected(()->f.readOnlyStore().load(run));
    }
    final class Harness {
        final JdbcTemplate db,authority;final String task,result,resultSha,turn;
        final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);final ProductRetentionPolicy retention=
                new ProductRetentionPolicy(POLICY,1,"b".repeat(64),Duration.ofDays(36500));
        final ProductSourceAvailability sources=mock(ProductSourceAvailability.class);
        final ProductSourceWatermark sourceWatermark=mock(ProductSourceWatermark.class);
        final ProductCollectedPatentTitles titles=mock(ProductCollectedPatentTitles.class);
        final ConsultationRetrievalModelPort model=mock(ConsultationRetrievalModelPort.class);
        final ProductRetrievalWorkflow workflow=mock(ProductRetrievalWorkflow.class);
        final ProductGrantWatermark watermark;final ProductGrants grants;final Map<String,String> grantIds=new HashMap<>();
        final Jwt jwt=Jwt.withTokenValue("own-test-not-issued-token").header("alg","RS256").issuer(ISSUER)
                .subject("own-member").claim("jti","own-jti").claim("sid","own-sid").build();
        final ProductRetrievalDialogue dialogue;final ProductRetrievalWorkspace workspace;
        final ProductConsultationAgentTools tools;final TransactionTemplate tx;final AgentCheckpointCodec codec;
        JdbcAgentCheckpointStore.Binding binding;Checkpoint seed;String before;
        Harness(Path root,boolean closed) throws Exception {
            var keepOpen=new JdbcDataSource();keepOpen.setURL("jdbc:h2:file:"+root.resolve("own-engineering").toString().replace('\\','/')+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            keepOpen.setUser("sa");keepOpen.setPassword("");new JdbcTemplate(keepOpen).execute("SET DB_CLOSE_DELAY -1");
            var base=new ProductHistoricalResultIsolationJdbcTest();base.temporary=root;
            Object copy=reflect(base,"create",new Class<?>[]{boolean.class},closed);
            db=(JdbcTemplate)reflect(copy,"db",new Class<?>[]{});result=(String)reflect(copy,"id",new Class<?>[]{});
            resultSha=(String)reflect(copy,"sha",new Class<?>[]{});task=db.queryForObject("SELECT task_id FROM con3_task",String.class);
            tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
            var ownAuthority=new JdbcDataSource();ownAuthority.setURL("jdbc:h2:file:"+root.resolve("own-authority").toString().replace('\\','/')+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            authority=new JdbcTemplate(ownAuthority);sql(db,"db/dm8/V032__product_identity.sql");
            sql(authority,"db/identity-authority/V001__grant_watermark.sql");
            authority.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,digest(""));
            db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,'own-member','own-encoded','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER,"own-member");
            var keys=new ProductSigningKeys(db,tx,ISSUER,Base64.getEncoder().encodeToString(new byte[32]),Duration.ofMinutes(5),Clock.systemUTC());
            String kid=keys.rotate("own-maintainer");
            db.update("INSERT INTO id2_session VALUES('own-sid','own-jti',?,'own-member','own-client',?,CURRENT_TIMESTAMP,?,'ACTIVE',NULL)",ISSUER,kid,Timestamp.from(Instant.now().plusSeconds(3600)));
            watermark=new ProductGrantWatermark(db,authority,ISSUER);grants=new ProductGrants(db,tx,Clock.systemUTC());
            for(String action:List.of("READ","WRITE"))grantIds.put(action,watermark.change(()->grants.issue(
                    new ProductGrants.Scope(ISSUER,"own-member","OWN_ORGANIZATION","CONSULTATION",task,"own-scope:1","TASK",task,1,action,"TASK_WORK"),
                    Instant.now().plusSeconds(3600),0,"own-maintainer","own-issue-"+action,"a".repeat(64))).grantId());
            extendSchema();var directory=new JdbcProductTaskDirectory(db,clock);
            var access=new ProductConsultationAccess(db,watermark,ISSUER,clock);
            var goals=new ProductTaskGoalReader(db,directory,access,retention,JSON,clock);
            var active=new ProductSourceAvailability.Active("OWN_SOURCE","OWN_CONTRACT","OWN_SOURCE_VERSION","OWN_DECISION",7,1,"own-no-original","a".repeat(64),"c".repeat(64));
            when(sources.require(anyString(),anyString(),anyString())).thenReturn(active);
            when(titles.fieldCitations(anyList(),anyMap(),anySet())).thenAnswer(call->{
                List<ProductPatentSearchExecution.Member> members=call.getArgument(0);return members.stream().map(m->
                        new ProductPatentSearchCitations.Citation(m.memberId(),m.sourceId(),m.documentId(),m.documentVersion(),m.sourceVersion(),
                        "https://own.invalid","https://own.invalid",NOW,m.rawSha(),m.locatorRef(),Map.of("CLAIMS",m.locatorRef()))).toList();});
            var cursors=new ProductPatentSearchCursor(db,new byte[32],Duration.ofHours(1),clock);
            var searches=new ProductPatentSearchExecution(db,null,null,titles,sources,sourceWatermark,directory,access,retention,JSON,cursors,clock,20);
            dialogue=new ProductRetrievalDialogue(db,directory,access,goals,searches,sources,model,tx,retention,JSON,clock);
            workspace=new ProductRetrievalWorkspace(db,directory,access,goals,sources,searches,clock,POLICY,1,dialogue);
            tools=new ProductConsultationAgentTools(workspace,workflow,dialogue,jwt,JSON);
            codec=new AgentCheckpointCodec(JSON,Base64.getEncoder().encodeToString(new byte[32]));
            turn=addTurn(result,resultSha,"EXPLANATION",NOW);clearInvocations(sources,sourceWatermark,titles);freeze();
        }
        void extendSchema() {
            for(String column:List.of("owner_issuer VARCHAR","owner_subject VARCHAR","state VARCHAR","authorization_version BIGINT","expires_at TIMESTAMP"))db.execute("ALTER TABLE con3_task ADD "+column);
            db.update("UPDATE con3_task SET owner_issuer=?,owner_subject='own-member',state='IN_PROGRESS',authorization_version=1,expires_at=?",ISSUER,Timestamp.from(NOW.plusSeconds(3600)));
            for(String ddl:List.of("con3_organization(organization_id VARCHAR,name VARCHAR,status VARCHAR)","con3_data_scope(scope_id VARCHAR,task_id VARCHAR,organization_id VARCHAR,scope_version BIGINT,kind VARCHAR)",
                    "con4_result_cursor(cursor_id VARCHAR)","con4_task_source_current(task_id VARCHAR,scope_id VARCHAR,scope_version BIGINT,status VARCHAR)",
                    "con4_task_source_scope(scope_id VARCHAR,scope_version BIGINT,source_id VARCHAR,task_version BIGINT,source_epoch BIGINT,source_row_version BIGINT,contract_version VARCHAR,decision_id VARCHAR,expires_at TIMESTAMP,organization_id VARCHAR,status VARCHAR)"))db.execute("CREATE TABLE "+ddl);
            db.update("INSERT INTO con3_organization VALUES('OWN_ORGANIZATION','own','ACTIVE')");
            db.update("INSERT INTO con3_data_scope VALUES('own-scope',?,'OWN_ORGANIZATION',1,'TASK_METADATA')",task);
            db.execute("ALTER TABLE con4_search_attempt ADD filters_json VARCHAR DEFAULT '{}'");db.execute("ALTER TABLE con4_selection_revision ADD revision BIGINT DEFAULT 1");
            for(String column:List.of("goal_id VARCHAR DEFAULT 'own-goal'","goal_revision BIGINT DEFAULT 1","requested_fields_json VARCHAR DEFAULT '[\"CLAIMS\"]'"))db.execute("ALTER TABLE con3_search_input ADD "+column);
            db.execute("CREATE TABLE con3_goal_current(task_id VARCHAR,goal_id VARCHAR,revision BIGINT,task_version BIGINT)");
            db.execute("CREATE TABLE con3_message(task_id VARCHAR,message_id VARCHAR,raw_text VARCHAR,expires_at TIMESTAMP)");
            db.execute("CREATE TABLE con3_goal_revision(task_id VARCHAR,goal_id VARCHAR,revision BIGINT,purpose VARCHAR,object_text VARCHAR,scope_json VARCHAR,features_json VARCHAR,confirmed_at TIMESTAMP,expires_at TIMESTAMP,retention_ref VARCHAR,retention_version BIGINT,retention_evidence_sha VARCHAR,source_message_id VARCHAR,status VARCHAR)");
            db.update("INSERT INTO con3_goal_current VALUES(?,'own-goal',1,1)",task);
            db.update("INSERT INTO con3_message VALUES(?,'own-message','own public query',?)",task,Timestamp.from(NOW.plusSeconds(3600)));
            db.update("INSERT INTO con3_goal_revision VALUES(?,'own-goal',1,'QUERY','own','[]','[]',?,?,?,1,?,'own-message','CONFIRMED')",task,Timestamp.from(NOW),Timestamp.from(NOW.plusSeconds(3600)),POLICY,retention.evidenceSha256());
            sql(db,"db/dm8/V089__product_consultation_model_turn.sql");sql(db,"db/dm8/V097__consultation_model_invocation.sql");sql(db,"db/dm8/V096__agent_runtime_checkpoint.sql");
        }
        String addTurn(String resultId,String sha,String kind,Instant created) {
            String id=UUID.randomUUID().toString();String request=resultId==null?id:"own-turn";
            db.update("INSERT INTO con3_model_turn VALUES(?,?,1,'own-member',?,?,?, ?,?,'own-model','own-prompt','own request','own raw',?, ?,?,?,1,?)",
                    id,task,kind,request,"a".repeat(64),resultId,sha,"{\"answer\":\"own answer\",\"citations\":[]}",Timestamp.from(created),Timestamp.from(NOW.plusSeconds(3600)),POLICY,retention.evidenceSha256());return id;
        }
        void revoke(String action){watermark.change(()->grants.revoke(grantIds.get(action),1,"own-maintainer","own-revoke-"+action,"d".repeat(64)));}
        String persist(boolean evaluation) {
            var goal=workspace.read(task,jwt).goal();String run=UUID.randomUUID().toString();
            var rtTask=new Task(task,ConsultationAgent.TASK_TYPE,"own-scope:1",new Goal(goal.goalId(),ProductConsultationAgentTools.version(goal),"own-query",ConsultationAgent.COMPLETION_CONTRACT));
            seed=new Checkpoint(run,ConsultationAgent.DEFINITION,rtTask,new Budget(5,0,Duration.ofHours(1)),NOW,0,Phase.OBSERVE,null,0,0,0,List.of(),null,null);
            binding=new JdbcAgentCheckpointStore.Binding(ConsultationAgent.DEFINITION.agentId(),task,goal.goalId(),ProductConsultationAgentTools.version(goal),rtTask.scope(),NOW.plusSeconds(3600));
            var store=store();store.commit(-1,seed,new AgentEvent(run,0,Phase.OBSERVE,null,null));
            if(evaluation) {var plan=new Plan("own-plan",List.of(new Step("own-draft","AnswerDraft","answer-draft","own-query")));
                var next=new Checkpoint(run,seed.definition(),rtTask,seed.budget(),NOW,1,Phase.EVALUATE,plan,1,1,0,List.of(new Observation("answer-turn:"+turn,resultSha)),null,null);
                store.commit(0,next,new AgentEvent(run,1,Phase.EVALUATE,null,null));}
            return run;
        }
        String persistRetrieval(String prefix) {
            String run=persist(false);var store=store();var next=new Checkpoint(run,seed.definition(),seed.task(),seed.budget(),NOW,1,
                    Phase.PLAN,null,0,0,0,List.of(new Observation(prefix+result,resultSha)),null,null);
            store.commit(0,next,new AgentEvent(run,1,Phase.PLAN,null,null));return run;
        }
        String installNewerResult() throws Exception {
            Path dir=Files.createDirectory(temporary.resolve("unrelated-closed-fixture"));
            var ds=new JdbcDataSource();ds.setURL("jdbc:h2:file:"+dir.resolve("own-engineering").toString().replace('\\','/')+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");ds.setUser("sa");
            new JdbcTemplate(ds).execute("SET DB_CLOSE_DELAY -1");
            var base=new ProductHistoricalResultIsolationJdbcTest();base.temporary=dir;Object copy=reflect(base,"create",new Class<?>[]{boolean.class},true);
            var other=(JdbcTemplate)reflect(copy,"db",new Class<?>[]{});String id=(String)reflect(copy,"id",new Class<?>[]{});
            other.update("UPDATE con4_search_attempt SET task_id=?,created_at=?",task,Timestamp.from(NOW.plusSeconds(2)));
            other.update("UPDATE con4_vector_build SET completed_at=?",Timestamp.from(NOW.plusSeconds(1)));
            // Distinct result/member IDs and a genuinely later own fixture build: it cannot verify the old execution.
            db.update("DELETE FROM con4_vector_build");
            for(String table:List.of("con4_search_attempt","con4_result_set","con4_search_request","con4_search_source","con4_result_member","con4_search_field_scope","con4_result_field_evidence","con4_vector_build")) {
                if(table.equals("con4_search_attempt"))other.execute("ALTER TABLE con4_search_attempt ADD filters_json VARCHAR DEFAULT '{}'");
                for(var row:other.queryForList("SELECT * FROM "+table))db.update("INSERT INTO "+table+"("+String.join(",",row.keySet())+") VALUES("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
            }
            other.execute("SHUTDOWN");
            return id;
        }
        JdbcAgentCheckpointStore store(){return new JdbcAgentCheckpointStore(db,tx,codec,binding,(b,c)->tools.current(c==null?seed:c),clock);}
        JdbcAgentCheckpointStore readOnlyStore(){return new JdbcAgentCheckpointStore(db,tx,codec,binding,(b,c)->{
            var live=workspace.read(task,jwt);var goal=live.goal();
            if(goal==null || !b.goalId().equals(goal.goalId()) || !b.goalVersion().equals(ProductConsultationAgentTools.version(goal)) || !b.scope().equals(live.task().dataScope()))throw ProductRetrievalWorkspace.conflict();
            // Reuse planned Controller READ dependency callback after live READ/Goal/source checks, not current()/WRITE or HTTP.
            if(c!=null)tools.requireDependencies(c);
        },clock);}
        ConsultationAgent restart(){return new ConsultationAgent(store(),tools,(c,s)->{tools.current(c);return true;},clock);}
        void freeze(){before=snapshot();}
        String snapshot(){var all=new TreeMap<String,List<List<String>>>();
            for(var entry:Map.of("",db,"AUTHORITY.",authority).entrySet()) {
                var tables=entry.getValue().query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' ORDER BY TABLE_NAME",(rs,n)->rs.getString(1));
                for(String table:tables){var rows=entry.getValue().query("SELECT * FROM "+table,(rs,n)->{List<String> row=new ArrayList<>();
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++){Object value=rs.getObject(i);row.add(value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value instanceof java.sql.Blob b?HexFormat.of().formatHex(b.getBytes(1,(int)b.length())):value instanceof java.sql.Clob c?c.getSubString(1,(int)c.length()):Objects.toString(value,null));}return row;});rows.sort(Comparator.comparing(Object::toString));all.put(entry.getKey()+table,rows);}
            }
            try{return JSON.writeValueAsString(all);}catch(Exception e){throw new IllegalStateException(e);}}
    }
    static Object reflect(Object target,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    static void sql(JdbcTemplate db,String path){new ResourceDatabasePopulator(new FileSystemResource(path)).execute(db.getDataSource());}
    public static void main(String[] args) {
        ConfigurationParameters params=new ConfigurationParameters(){public Optional<String> get(String k){return Optional.empty();}
            public Optional<Boolean> getBoolean(String k){return Optional.empty();}public int size(){return 0;}public Set<String> keySet(){return Set.of();}};
        var selector=DiscoverySelectors.selectClass(ProductHistoricalIndirectConsumptionTest.class);
        EngineDiscoveryRequest request=new EngineDiscoveryRequest(){public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> t){return t.isInstance(selector)?List.of(t.cast(selector)):List.of();}
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> t){return List.of();}public ConfigurationParameters getConfigurationParameters(){return params;}};
        var engine=new JupiterTestEngine();var root=engine.discover(request,UniqueId.forEngine(engine.getId()));int[] counts={0,0,0};
        engine.execute(new ExecutionRequest(root,new EngineExecutionListener(){public void executionSkipped(TestDescriptor d,String reason){counts[2]++;System.out.println("SKIPPED "+d.getDisplayName());}
            public void executionFinished(TestDescriptor d,TestExecutionResult r){if(d.isTest()&&r.getStatus()==TestExecutionResult.Status.SUCCESSFUL){counts[0]++;System.out.println("PASSED "+d.getDisplayName());}
                if(r.getStatus()==TestExecutionResult.Status.FAILED){counts[1]++;System.out.println("FAILED "+d.getDisplayName());r.getThrowable().orElseThrow().printStackTrace(System.out);}}},params));
        System.out.printf("successfulTests=%d failedDescriptors=%d skippedTests=%d REAL_HISTORICAL_SAMPLES=0%n",counts[0],counts[1],counts[2]);
        if(counts[0]!=19||counts[1]!=0||counts[2]!=0)System.exit(1);
    }
}
