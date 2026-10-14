package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit real-I suite: run by name; absent facilities FAIL, never conditional skip. Controlled inputs are not business facts. */
class PenaltyStoreDm8IT {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String run=required("ENF_PENALTY_RUN_ID");
    final Path root=Path.of(required("ENF_PENALTY_FILE_ROOT"));
    final Path evidence=Path.of(required("ENF_PENALTY_EVIDENCE"));
    final DriverManagerDataSource source=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
    final JdbcTemplate db=new JdbcTemplate(source);
    Dm8PenaltyStore store()throws Exception {
        assertTrue(source.getUrl().startsWith("jdbc:dm://"),"Real DM8 required");
        if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF11_PENALTY_REVISION'",Integer.class)==0)
            try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(required("ENF_PENALTY_MIGRATION")));}
        return new Dm8PenaltyStore(db,new TransactionTemplate(new DataSourceTransactionManager(source)),json,Clock.systemUTC(),new PenaltyArtifacts(root));
    }
    @Test void rulesTwoConnectionsCasReplayRollbackAndOriginalFailClosed()throws Exception {
        var a=store();var b=store();String id="RULE-"+run;String key="save-"+run;
        var original=a.command(key,"a".repeat(64),"CONTROLLED_TECHNICAL_INPUT","SAVE_PENALTY_RULE",()->{
            a.appendRule(rule(id,1,RuleState.DRAFT),0,"CONTROLLED_TECHNICAL_INPUT");return new Receipt(key,"SAVE_PENALTY_RULE","PENALTY_RULE",id,1);});
        assertEquals(original,b.command(key,"a".repeat(64),"CONTROLLED_TECHNICAL_INPUT","SAVE_PENALTY_RULE",()->{throw new AssertionError("Replay mutated");}));
        assertThrows(IllegalArgumentException.class,()->b.command(key,"b".repeat(64),"CONTROLLED_TECHNICAL_INPUT","SAVE_PENALTY_RULE",()->original));
        int filesBefore=fileCount();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->publish(a,start,id,"pa-"+run));var y=pool.submit(()->publish(b,start,id,"pb-"+run));start.countDown();
            assertEquals(1,x.get(30,TimeUnit.SECONDS)+y.get(30,TimeUnit.SECONDS));
        }
        assertEquals(filesBefore+1,fileCount());assertEquals(2,b.ruleHistory(id).size());assertEquals(RuleState.DRAFT,b.rule(id,1).orElseThrow().state());
        int before=fileCount();
        assertThrows(IllegalStateException.class,()->a.command("rollback-"+run,"c".repeat(64),"CONTROLLED_TECHNICAL_INPUT","DISABLE",()->{
            a.appendRule(rule(id,3,RuleState.DISABLED),2,"CONTROLLED_TECHNICAL_INPUT");throw new IllegalStateException("Injected after physical original and DB append");}));
        assertEquals(before,fileCount());assertEquals(2,b.ruleHistory(id).size());assertEquals(2,receiptCount(id));
        String disable="disable-"+run;b.command(disable,"d".repeat(64),"CONTROLLED_TECHNICAL_INPUT","DISABLE",()->{
            b.appendRule(rule(id,3,RuleState.DISABLED),2,"CONTROLLED_TECHNICAL_INPUT");return new Receipt(disable,"DISABLE","PENALTY_RULE",id,3);});
        assertEquals(RuleState.DISABLED,a.latestRule(id).orElseThrow().state());
        var record=db.queryForMap("SELECT artifact_key,artifact_sha,payload FROM enf11_penalty_revision WHERE resource_id=? AND revision=3",id);
        Path originalFile=root.resolve(record.get("ARTIFACT_KEY").toString());byte[] bytes=Files.readAllBytes(originalFile);
        assertEquals(record.get("ARTIFACT_SHA").toString(),PenaltyArtifacts.sha(bytes));
        Path missing=root.resolve("missing-"+UUID.randomUUID());Files.move(originalFile,missing);
        try{assertThrows(IllegalStateException.class,()->b.latestRule(id));assertEquals(3,receiptCount(id));}finally{Files.move(missing,originalFile);}
        try{Files.writeString(originalFile,"tampered");assertThrows(IllegalStateException.class,()->b.latestRule(id));assertEquals(3,receiptCount(id));}finally{Files.write(originalFile,bytes);}
        assertEquals(rule(id,3,RuleState.DISABLED),b.latestRule(id).orElseThrow());
        writeEvidence("rule",id,a.ruleHistory(id),Map.of("casWinners",1,"rollbackDbFileSideEffects",0,"missingOriginalFailClosed",true,"shaTamperFailClosed",true));
    }
    @Test void suggestionHistoryReplayAndDbFileEventReceiptReconcile()throws Exception {
        var a=store();var b=store();String id="SUGGESTION-"+run;
        for(int version=1;version<=4;version++) {
            long r=version;String key="suggestion-"+version+"-"+run;
            SuggestionState state=switch(version){case 1->SuggestionState.WAITING_INPUT;case 2,3->SuggestionState.PENDING_REVIEW;default->SuggestionState.REVIEWED;};
            var value=suggestion(id,r,state);
            Receipt receipt=a.command(key,String.valueOf(version).repeat(64),"CONTROLLED_TECHNICAL_INPUT","SUGGESTION_VERSION",()->{
                a.appendSuggestion(value,r-1,"CONTROLLED_TECHNICAL_INPUT");return new Receipt(key,"SUGGESTION_VERSION","PENALTY_SUGGESTION",id,r);});
            assertEquals(receipt,b.command(key,String.valueOf(version).repeat(64),"CONTROLLED_TECHNICAL_INPUT","SUGGESTION_VERSION",()->{throw new AssertionError("Replay mutated");}));
        }
        assertEquals(4,b.suggestionHistory(id).size());assertEquals(SuggestionState.WAITING_INPUT,b.suggestion(id,1).orElseThrow().state());
        assertEquals(SuggestionState.REVIEWED,b.latestSuggestion(id).orElseThrow().state());
        assertThrows(IllegalArgumentException.class,()->a.command("stale-"+run,"e".repeat(64),"CONTROLLED_TECHNICAL_INPUT","SUGGESTION_VERSION",()->{
            a.appendSuggestion(suggestion(id,4,SuggestionState.RETURNED),3,"CONTROLLED_TECHNICAL_INPUT");return new Receipt("stale-"+run,"SUGGESTION_VERSION","PENALTY_SUGGESTION",id,4);}));
        assertEquals(4,receiptCount(id));writeEvidence("suggestion",id,a.suggestionHistory(id),Map.of("replaySideEffects",0,"staleWriteSideEffects",0));
    }
    int publish(Dm8PenaltyStore s,CountDownLatch latch,String id,String key)throws Exception {
        latch.await();try{s.command(key,"f".repeat(64),"CONTROLLED_TECHNICAL_INPUT","PUBLISH",()->{
            s.appendRule(rule(id,2,RuleState.ACTIVE),1,"CONTROLLED_TECHNICAL_INPUT");return new Receipt(key,"PUBLISH","PENALTY_RULE",id,2);});return 1;}
        catch(IllegalArgumentException expected){assertEquals("Penalty version conflict",expected.getMessage());return 0;}
    }
    void writeEvidence(String kind,String id,Object history,Map<String,Object> checks)throws Exception {
        var receipts=db.queryForList("SELECT request_key,revision,operation,resource_kind,resource_id FROM enf11_penalty_receipt WHERE resource_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,revision,operation,resource_kind,resource_id FROM enf11_penalty_event WHERE resource_id=? ORDER BY revision",id);assertEquals(receipts,events);
        var artifacts=db.queryForList("SELECT revision,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_id=? ORDER BY revision",id);
        for(var artifact:artifacts){Path file=root.resolve(artifact.get("ARTIFACT_KEY").toString());assertEquals(artifact.get("ARTIFACT_SHA").toString(),PenaltyArtifacts.sha(Files.readAllBytes(file)));}
        json.writeValue(evidence.resolve(kind+"-store-I.json").toFile(),Map.of("runId",run,"scope","REAL_DM8_STORE_I_NOT_PRODUCT_ACCEPTANCE","database","REAL_DM8","resourceId",id,"history",history,"receipts",receipts,"events",events,"originals",artifacts,"checks",checks));
    }
    int fileCount()throws Exception{try(var files=Files.list(root)){return (int)files.count();}}
    int receiptCount(String id){return db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_receipt WHERE resource_id=?",Integer.class,id);}
    Rule rule(String id,long revision,RuleState state) {
        var formula=new PenaltyFormula.Binary(PenaltyFormula.Operator.MULTIPLY,new PenaltyFormula.Input("B"),new PenaltyFormula.Constant(new BigDecimal("2"),"PROJECT_FACTOR@1"));
        var calculation=new PenaltyPublishChecks.Input("ORG-CONTROLLED",true,"PROJECT_BASIS",1,formula,
            new PenaltyFormula.Semantics("PROJECT_SCORE","NONE","POINT",2,RoundingMode.HALF_UP),new PenaltyFormula.Range(BigDecimal.ZERO,new BigDecimal("100"),"PROJECT_BASIS",1),List.of(),List.of());
        return new Rule(id,revision,"ORG-CONTROLLED","Explicit store-I input",state,new Scope("PROJECT","PROJECT","PROJECT","PROJECT",LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)),null,List.of(),calculation);
    }
    Suggestion suggestion(String id,long revision,SuggestionState state) {
        var input=new CaseInput("PROJECT_CASE",1,"ORG-CONTROLLED",LocalDate.of(2026,10,1),"PROJECT","PROJECT","PROJECT",List.of(),Map.of(),List.of(),List.of());
        return new Suggestion(id,revision,"ORG-CONTROLLED",state,input,List.of(),List.of(),List.of("EXPLICIT_STORE_INPUT_NOT_BUSINESS_CONFIRMATION"),null,null,null);
    }
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; real I must not skip");return value;}
}
