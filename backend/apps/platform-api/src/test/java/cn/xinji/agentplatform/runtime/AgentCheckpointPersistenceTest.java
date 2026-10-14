package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import cn.xinji.agentplatform.runtime.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentCheckpointPersistenceTest {
    JdbcTemplate db; TransactionTemplate tx; AgentCheckpointCodec codec;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC);
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    final Definition definition=new Definition("consultation-agent","1",Set.of("search"),Map.of("retrieve",Set.of("search")),"p1","policy1","e1");
    final Task task=new Task("task","search","scope",new Goal("goal","1","input-reference","evidence-supported/v1"));
    final Budget budget=new Budget(4,1,Duration.ofMinutes(2));
    JdbcAgentCheckpointStore.Binding binding;
    @BeforeEach void setup() throws Exception {
        var source=newSource();
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        codec=new AgentCheckpointCodec(new ObjectMapper().findAndRegisterModules(),key);
        binding=new JdbcAgentCheckpointStore.Binding(definition.agentId(),task.id(),"goal","1","scope",clock.instant().plusSeconds(3600));
        String sql=Files.readString(Path.of("db/dm8/V096__agent_runtime_checkpoint.sql"));
        for(String statement:sql.replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())db.execute(statement);
    }
    DriverManagerDataSource newSource() throws Exception {
        return new DriverManagerDataSource("jdbc:h2:mem:agent"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
    }
    @AfterEach void cleanup() throws Exception {if(db!=null)db.execute("SHUTDOWN");}
    JdbcAgentCheckpointStore store(JdbcAgentCheckpointStore.CurrentExecution access) {
        return new JdbcAgentCheckpointStore(db,tx,codec,binding,access,clock);
    }
    Checkpoint initial() {return new Checkpoint("run",definition,task,budget,clock.instant(),0,Phase.OBSERVE,null,0,0,0,List.of(),null,null);}
    Checkpoint plan() {return new Checkpoint("run",definition,task,budget,clock.instant(),1,Phase.PLAN,null,0,0,0,List.of(new Observation("source/v1","a".repeat(64))),null,null);}
    AgentEvent event(Checkpoint c) {return new AgentEvent(c.runId(),c.version(),c.phase(),null,null);}
    @Test void actualSqlRoundtripAndNewAdapterRecoveryPreserveAtomicHistory() {
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));s.commit(0,plan(),event(plan()));
        assertEquals(plan(),store((b,c)->{}).load("run"));assertEquals(2,s.events("run").size());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Integer.class));
        String encrypted=db.queryForObject("SELECT ciphertext FROM agent_run_revision WHERE revision=1",String.class);
        assertFalse(encrypted.contains("source/v1"));assertFalse(encrypted.contains("input-reference"));
    }
    @Test void actualRevisionConflictRollsBackHeadCas() {
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));
        var encoded=codec.encode(plan(),event(plan()));
        db.update("INSERT INTO agent_run_revision(run_id,revision,ciphertext,nonce,payload_sha,created_at) VALUES('run',1,?,?,?,CURRENT_TIMESTAMP)",encoded.ciphertext(),encoded.nonce(),encoded.sha256());
        assertThrows(RuntimeException.class,()->s.commit(0,plan(),event(plan())));
        assertEquals(0,db.queryForObject("SELECT head_version FROM agent_run_head",Long.class));
    }
    @Test void currentAuthorizationFailureBeforeCommitRollsBackEntireCreation() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var s=store((b,c)->{if(calls.incrementAndGet()==2)throw new SecurityException("REVOKED");});
        assertThrows(SecurityException.class,()->s.commit(-1,initial(),event(initial())));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_run_revision",Integer.class));
    }
    @Test void staleCasAndDuplicateCreateCannotAppendOrOverwriteEvidence() {
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));s.commit(0,plan(),event(plan()));
        assertThrows(RuntimeException.class,()->s.commit(-1,initial(),event(initial())));
        assertThrows(IllegalStateException.class,()->s.commit(0,plan(),event(plan())));
        assertEquals(2,s.events("run").size());
    }
    @Test void realConcurrentTransactionsHaveExactlyOneHeadWinner() throws Exception {
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> action=()->{start.await();try{store((b,c)->{}).commit(0,plan(),event(plan()));return true;}catch(IllegalStateException conflict){return false;}};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(2,s.events("run").size());
        } finally {pool.shutdownNow();}
    }
    @Test void ciphertextShaNonceAndCrossRunTransplantsFailClosed() {
        var encoded=codec.encode(initial(),event(initial()));
        assertThrows(IllegalStateException.class,()->codec.decode("other",definition.agentId(),task.id(),0,encoded));
        assertThrows(IllegalStateException.class,()->codec.decode("run",definition.agentId(),task.id(),0,new AgentCheckpointCodec.Encoded(encoded.ciphertext(),encoded.nonce(),"b".repeat(64))));
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));db.update("UPDATE agent_run_revision SET nonce=?","AAAAAAAAAAAAAAAA");
        assertThrows(IllegalStateException.class,()->s.load("run"));
    }
    @Test void definitionTaskAndBudgetCannotChangeAfterCreation() {
        var s=store((b,c)->{});s.commit(-1,initial(),event(initial()));
        var changed=new Checkpoint("run",definition,task,new Budget(99,1,Duration.ofMinutes(2)),clock.instant(),1,Phase.PLAN,null,0,0,0,List.of(),null,null);
        assertThrows(IllegalStateException.class,()->s.commit(0,changed,event(changed)));assertEquals(initial(),s.load("run"));
    }
    @Test void currentRevocationBlocksRecoveredReadEvenWithValidHistoricalCiphertext() {
        store((b,c)->{}).commit(-1,initial(),event(initial()));
        assertThrows(SecurityException.class,()->store((b,c)->{throw new SecurityException("REVOKED");}).load("run"));
    }
    @Test void expiryDeniesReadAndPurgeRemovesOnlyItsExpiredParent() {
        store((b,c)->{}).commit(-1,initial(),event(initial()));
        var expired=new JdbcAgentCheckpointStore(db,tx,codec,binding,(b,c)->{},Clock.offset(clock,Duration.ofHours(2)));
        assertThrows(SecurityException.class,()->expired.load("run"));assertEquals(1,expired.purgeExpired());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));
    }
    @Test void schemaReversalAndForwardReapplicationWork() throws Exception {
        for(String statement:Files.readString(Path.of("db/dm8/U096__agent_runtime_checkpoint.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())db.execute(statement);
        for(String statement:Files.readString(Path.of("db/dm8/V096__agent_runtime_checkpoint.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!statement.isBlank())db.execute(statement);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_run_head",Integer.class));
    }
}
