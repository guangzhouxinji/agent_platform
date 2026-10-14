package cn.xinji.agentplatform.adjudication.casework;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;
class MaterialRunStoreTest {
    @TempDir Path keys;
    JdbcTemplate db;MaterialRunVault vault;JdbcMaterialRunStore store;MaterialModelPolicy policy;
    Instant now=Instant.parse("2026-10-03T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    @BeforeEach void setup()throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:adjmodel-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V090__adjudication_material_model_run.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V091__adjudication_model_approval_binding.sql"));}
        db=new JdbcTemplate(ds);vault=new MaterialRunVault(keys,clock);store=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),vault,clock);
        policy=new MaterialModelPolicy("component-policy",sha256("approval"),sha256("supplier"),"case1",now.plus(Duration.ofDays(30)),Set.of(sha256("material")),"deepseek-flash");store.initialize(policy);
    }
    JdbcMaterialRunStore.Reservation reserve(String key){return store.reserve(policy,key,"actor",sha256(key),500,100);}
    @Test void encryptedLongTextRestartsAndReplaysWithoutAnotherCall(){var r=reserve("k1");String text="长原文😀".repeat(12000);store.complete(policy,r,50,20,text,true);assertFalse(db.queryForObject("SELECT payload FROM adj3_material_run",String.class).contains("长原文"));var reload=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),new MaterialRunVault(keys,clock),clock);assertTrue(text.equals(reload.load(policy,r.id())));assertTrue(reserve("k1").replay());assertEquals(1,reload.totals(policy).calls());}
    @Test void uncertainCallKeepsQuotaAndCannotRetry(){var r=reserve("k1");store.uncertain(r);assertEquals(500,store.totals(policy).inputTokens());assertThrows(IllegalArgumentException.class,()->reserve("k1"));}
    @Test void sameKeyDifferentRequestOrActorConflict(){var r=reserve("k1");store.complete(policy,r,50,20,"payload",true);assertThrows(IllegalArgumentException.class,()->store.reserve(policy,"k1","other",sha256("k1"),500,100));assertThrows(IllegalArgumentException.class,()->store.reserve(policy,"k1","actor",sha256("changed"),500,100));}
    @Test void twentyCallsLimitPersistsAcrossRestart(){for(int i=0;i<20;i++)store.uncertain(reserve("k"+i));assertThrows(IllegalArgumentException.class,()->reserve("overflow"));assertEquals(20,store.totals(policy).calls());}
    @Test void inputAndOutputTotalsCannotExceed(){store.uncertain(store.reserve(policy,"k1","actor",sha256("k1"),99999,1));assertThrows(IllegalArgumentException.class,()->store.reserve(policy,"k2","actor",sha256("k2"),2,1));}
    @Test void outputBudgetStopsWithoutExtraCall(){for(int i=0;i<9;i++)store.uncertain(store.reserve(policy,"k"+i,"actor",sha256("k"+i),1,2048));assertThrows(IllegalArgumentException.class,()->store.reserve(policy,"k9","actor",sha256("k9"),1,2048));assertEquals(9,store.totals(policy).calls());}
    @Test void providerUsageExceedingReservationFreezes(){var r=reserve("k1");assertThrows(IllegalStateException.class,()->store.complete(policy,r,501,20,"data",true));assertTrue(store.totals(policy).frozen());assertThrows(IllegalArgumentException.class,()->reserve("k2"));}
    @Test void failedOutputDoesNotReplacePriorSuccess(){var good=reserve("good");store.complete(policy,good,50,20,"good payload",true);var bad=reserve("bad");store.complete(policy,bad,50,20,"bad output",false);assertEquals("good payload",store.load(policy,good.id()));assertThrows(IllegalArgumentException.class,()->store.load(policy,bad.id()));}
    @Test void tamperedCiphertextAndMissingKeyFailClosed()throws Exception{var r=reserve("k1");store.complete(policy,r,50,20,"payload",true);Files.delete(keys.resolve(r.id()+".key"));assertThrows(IllegalStateException.class,()->store.load(policy,r.id()));db.update("UPDATE adj3_material_run SET payload='changed' WHERE run_id=?",r.id());assertThrows(IllegalStateException.class,()->store.load(policy,r.id()));}
    @Test void expiryCannotRestoreOrReadAndPurgesOnlyModelRows()throws Exception{var r=reserve("k1");store.complete(policy,r,50,20,"payload",true);String cipher=db.queryForObject("SELECT payload FROM adj3_material_run",String.class);db.execute("CREATE TABLE existing_case_history(id INTEGER PRIMARY KEY)");db.update("INSERT INTO existing_case_history VALUES(1)");Clock later=Clock.fixed(now.plus(Duration.ofDays(31)),ZoneOffset.UTC);var expiredVault=new MaterialRunVault(keys,later);var expired=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),expiredVault,later);assertThrows(IllegalStateException.class,()->expiredVault.open(r.id(),r.expiresAt(),cipher));expired.purgeExpired();assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM adj3_material_run",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM existing_case_history",Integer.class));assertFalse(Files.exists(keys.resolve(r.id()+".key")));assertThrows(SecurityException.class,()->expired.initialize(policy));}
    @Test void clockRollbackAndPolicyChangingCannotResetBudget(){var r=reserve("k1");store.uncertain(r);var other=new MaterialModelPolicy(policy.approvalRef(),policy.approvalSha256(),policy.supplierApprovalSha256(),policy.caseId(),policy.validUntil().plusSeconds(1),policy.permittedTextHashes(),policy.modelVersion());assertThrows(SecurityException.class,()->store.initialize(other));var earlier=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),vault,Clock.fixed(now.minusSeconds(5),ZoneOffset.UTC));assertThrows(SecurityException.class,()->earlier.reserve(policy,"k2","actor",sha256("k2"),500,100));}
    @Test void budgetTamperingRejected(){var r=reserve("k1");store.uncertain(r);db.update("UPDATE adj3_model_budget SET calls=0");assertThrows(IllegalStateException.class,()->reserve("k2"));}
    @Test void simultaneousLastSlotCannotOverspend()throws Exception{for(int i=0;i<19;i++)store.uncertain(reserve("k"+i));var pool=Executors.newFixedThreadPool(2);try{var futures=pool.invokeAll(List.of(()->{try{store.uncertain(reserve("last1"));return true;}catch(IllegalArgumentException e){return false;}},()->{try{store.uncertain(reserve("last2"));return true;}catch(IllegalArgumentException e){return false;}}));int successes=0;for(var f:futures)if(Boolean.TRUE.equals(f.get()))successes++;assertEquals(1,successes);assertEquals(20,store.totals(policy).calls());}finally{pool.shutdownNow();}}
    @Test void sameApprovalCannotResetQuotaByChangingCaseOrEvidenceHash(){var r=reserve("existing");store.uncertain(r);var other=new MaterialModelPolicy(policy.approvalRef(),sha256("edited-evidence"),policy.supplierApprovalSha256(),"other-case",policy.validUntil(),policy.permittedTextHashes(),policy.modelVersion());assertThrows(RuntimeException.class,()->store.initialize(other));assertEquals(1,store.totals(policy).calls());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM adj3_model_budget",Integer.class));}
    @Test void unreconciledRestoredAllowanceCannotBeBypassedWithNewCase(){reserve("existing");db.update("UPDATE adj3_model_budget SET approval_key=NULL");var other=new MaterialModelPolicy(policy.approvalRef(),policy.approvalSha256(),policy.supplierApprovalSha256(),"other-case",policy.validUntil(),policy.permittedTextHashes(),policy.modelVersion());assertThrows(IllegalStateException.class,()->store.initialize(other));store.initialize(policy);assertEquals(1,store.totals(policy).calls());}

}
