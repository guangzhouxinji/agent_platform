package cn.xinji.agentplatform.enforcement.documentrule;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real DM8 store I only: not a substitute for product HTTP/UI/authorization/recovery acceptance. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="ENF_DOCUMENT_RULE_STORE_EVIDENCE",matches=".+")
class DocumentRuleStoreDm8Test {
    @Test void immutableVersionsTwoConnectionsCasReplayAndRollback() throws Exception {
        var source=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),
                required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertTrue(source.getUrl().startsWith("jdbc:dm://"),"Real DM8 required");
        var db=new JdbcTemplate(source);
        if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF10_DOCUMENT_RULE'",Integer.class)==0) {
            Path root=Path.of("").toAbsolutePath();
            while(root!=null&&!java.nio.file.Files.isDirectory(root.resolve("db/dm8")))root=root.getParent();
            assertNotNull(root);
            try(var connection=source.getConnection()) { ScriptUtils.executeSqlScript(connection,
                new FileSystemResource(root.resolve("db/dm8/V080__enforcement_v2_document_rule.sql"))); }
        }
        var json=new ObjectMapper().findAndRegisterModules();
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var a=new Dm8DocumentRuleStore(db,tx,json,Clock.systemUTC());
        var b=new Dm8DocumentRuleStore(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)),json,Clock.systemUTC());
        String id="ENF13-STORE-"+UUID.randomUUID();
        String key="create-"+id;
        var draft=rule(id,1,State.DRAFT);
        var original=a.command(key,"a".repeat(64),"controlled-input","SAVE_DOCUMENT_RULE",()->{
            a.append(draft,0,"controlled-input");return new DocumentRuleService.Receipt(key,"SAVE_DOCUMENT_RULE",id,1); });
        assertEquals(original,b.command(key,"a".repeat(64),"controlled-input","SAVE_DOCUMENT_RULE",()->{throw new AssertionError("Replay mutated data");}));
        assertThrows(IllegalArgumentException.class,()->a.command(key,"b".repeat(64),"controlled-input","SAVE_DOCUMENT_RULE",()->original));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->race(a,gate,id,"publish-a-"+id));
            var second=pool.submit(()->race(b,gate,id,"publish-b-"+id));
            gate.countDown();
            assertEquals(1,first.get(30,TimeUnit.SECONDS)+second.get(30,TimeUnit.SECONDS));
        }
        assertEquals(2,a.history(id).size());
        assertEquals(State.DRAFT,a.read(id,1).orElseThrow().state());
        assertEquals(State.PUBLISHED,b.latest(id).orElseThrow().state());
        assertThrows(IllegalStateException.class,()->a.command("rollback-"+id,"c".repeat(64),"controlled-input","DISABLE",()->{
            a.append(rule(id,3,State.DISABLED),2,"controlled-input");throw new IllegalStateException("Injected after append before receipt"); }));
        assertEquals(2,b.history(id).size());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM enf10_document_rule_receipt WHERE rule_id=?",Integer.class,id));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM enf10_document_rule_event WHERE rule_id=?",Integer.class,id));
        String disabled="disable-"+id;
        b.command(disabled,"d".repeat(64),"controlled-input","DISABLE",()->{
            b.append(rule(id,3,State.DISABLED),2,"controlled-input");return new DocumentRuleService.Receipt(disabled,"DISABLE",id,3);});
        assertEquals(State.DISABLED,a.latest(id).orElseThrow().state());
        assertEquals(3,a.history(id).size());
        var receipts=db.queryForList("SELECT request_key,revision,operation FROM enf10_document_rule_receipt WHERE rule_id=? ORDER BY revision",id);
        var events=db.queryForList("SELECT request_key,revision,operation FROM enf10_document_rule_event WHERE rule_id=? ORDER BY revision",id);
        assertEquals(receipts,events);
        String output=required("ENF_DOCUMENT_RULE_STORE_EVIDENCE");
        json.writeValue(Path.of(output).toFile(),java.util.Map.of("database","REAL_DM8","ruleId",id,
            "history",a.history(id),"receipts",receipts,"events",events,"casWinners",1,"rollbackSideEffects",0,
            "scope","STORE_TRANSACTION_I_NOT_PRODUCT_ACCEPTANCE"));
    }
    private int race(Dm8DocumentRuleStore store,CountDownLatch gate,String id,String key)throws Exception {
        gate.await();
        try {
            store.command(key,"e".repeat(64),"controlled-input","PUBLISH",()->{
                store.append(rule(id,2,State.PUBLISHED),1,"controlled-input");return new DocumentRuleService.Receipt(key,"PUBLISH",id,2);});
            return 1;
        }catch(IllegalArgumentException expected){assertEquals("Rule version conflict",expected.getMessage());return 0;}
    }
    private Rule rule(String id,long revision,State state) {
        return new Rule(id,revision,"ORG-ENF-FUNCTIONAL",state,"EXPLICIT_STORE_TEST_TEMPLATE",1,"f".repeat(64),
                "EXPLICIT_STORE_TEST_SCHEMA","1",List.of());
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException(name+" required; test must not skip");
        return value;
    }
}
