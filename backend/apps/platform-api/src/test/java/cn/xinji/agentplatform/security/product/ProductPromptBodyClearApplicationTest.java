package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Application ordering and explicit recovery over the existing real two-H2 maintenance fixture. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptBodyClearApplicationTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{tls.close();}
    ProductPromptBodyClearStoreFixture fixture(int count)throws Exception{
        return new ProductPromptBodyClearStoreFixture(root.resolve(UUID.randomUUID().toString()),tls,count);
    }

    @Test void freshRepeatedAndReconstructedCallsReturnOneCommittedReceipt()throws Exception{
        try(var f=fixture(2)){
            var app=f.application(0,false);var events=f.f.snapshot(MAIN,"conp_event");var business=f.f.snapshot(MAIN,"conp_receipt");
            Receipt receipt=clear(app,f,0,"original");String original=manifest(f);
            assertEquals(receipt,clear(app,f,0,"original"));assertEquals(receipt,lookup(f.application(0,true),f,0,"original"));
            assertEquals(original,manifest(f));assertEquals("READY",f.state());assertEquals("CLEARED",f.bodyState(0));
            assertEquals("PRESENT",f.bodyState(1));assertEquals(1,f.count(true,"conp_prompt_cleanup_receipt"));
            assertEquals(1,f.count(false,"idp_prompt_cleanup_request"));assertEquals(events,f.f.snapshot(MAIN,"conp_event"));
            assertEquals(business,f.f.snapshot(MAIN,"conp_receipt"));
        }
    }
    @Test void failedMainLeavesGetAllBeforeReadOnlyAndPostResumesOriginalMaterial()throws Exception{
        try(var f=fixture(1)){
            var app=f.application(0,false);f.f.failSql(MAIN,sql->sql.startsWith("UPDATE conp_revision SET revision_json=NULL"));
            assertThrows(RuntimeException.class,()->clear(app,f,0,"resume"));String original=manifest(f);
            assertNoCompletion(f);assertEquals("CLEANUP_PENDING",f.state());assertEquals("PRESENT",f.bodyState(0));
            var query=f.application(0,true);reset(f);
            assertThrows(IllegalStateException.class,()->lookup(query,f,0,"resume"));assertNoWrites(f);
            assertEquals(original,manifest(f));assertEquals(0,f.count(true,"conp_prompt_cleanup_receipt"));
            var retry=f.application(0,true);reset(f);Receipt result=clear(retry,f,0,"resume");
            assertEquals(original,manifest(f));assertEquals("READY",f.state());
            assertTrue(f.f.authorityFault.sql.stream().noneMatch(sql->sql.startsWith("INSERT INTO idp_prompt_cleanup_request")));
            assertEquals(result,lookup(f.application(0,true),f,0,"resume"));
        }
    }
    @Test void exactIntentBoundaryDoesNotRenewAllBeforeOnEitherMethod()throws Exception{
        try(var f=fixture(1)){
            f.f.failSql(MAIN,sql->sql.startsWith("UPDATE conp_revision SET revision_json=NULL"));
            assertThrows(RuntimeException.class,()->clear(f.application(0,false),f,0,"expired"));
            String original=manifest(f);var saved=f.store.codec.decode(original);f.f.now=saved.intentUntil();
            var get=f.application(0,true);reset(f);assertThrows(IllegalStateException.class,()->lookup(get,f,0,"expired"));assertNoWrites(f);
            var post=f.application(0,true);reset(f);assertThrows(IllegalStateException.class,()->clear(post,f,0,"expired"));assertNoWrites(f);
            assertEquals(original,manifest(f));assertEquals("PRESENT",f.bodyState(0));assertEquals("CLEANUP_PENDING",f.state());
        }
    }
    @ParameterizedTest @ValueSource(strings={"resume","different"})
    void closedIndependentWorkspaceRefusesPendingClearBeforeMutationAndKeyClassification(String retryKey)throws Exception{
        try(var f=fixture(1)){
            f.f.failSql(MAIN,sql->sql.startsWith("UPDATE conp_revision SET revision_json=NULL"));
            assertThrows(RuntimeException.class,()->clear(f.application(0,false),f,0,"resume"));
            String original=manifest(f);assertEquals("CLEANUP_PENDING",f.state());assertEquals("PRESENT",f.bodyState(0));
            assertEquals(0,f.count(true,"conp_prompt_cleanup_receipt"));
            f.f.independent.update("UPDATE idp_workspace SET state='CLOSED' WHERE workspace_id=?",f.f.workspaceId);
            var app=f.application(0,true);reset(f);
            RuntimeException failure=assertThrows(RuntimeException.class,()->clear(app,f,0,retryKey));
            assertFalse(failure instanceof ProductPromptBodyClearConflict);assertNoWrites(f);
            assertTrue(f.f.mainFault.sql.stream().noneMatch(sql->sql.startsWith("UPDATE conp_revision SET revision_json=NULL")));
            assertEquals("PRESENT",f.bodyState(0));assertEquals(0,f.count(true,"conp_prompt_cleanup_receipt"));assertEquals(original,manifest(f));
        }
    }
    @Test void getSettlesCommittedMainAfterOriginalIntentWithoutAnotherClear()throws Exception{
        try(var f=fixture(1)){
            f.f.failSql(AUTHORITY,sql->sql.startsWith("UPDATE idp_revision_witness SET body_state='CLEARED'"));
            assertThrows(RuntimeException.class,()->clear(f.application(0,false),f,0,"late"));
            String original=manifest(f);var saved=f.store.codec.decode(original);assertEquals("CLEARED",f.bodyState(0));
            f.f.now=saved.intentUntil().plusMillis(1);var get=f.application(0,true);reset(f);
            Receipt result=lookup(get,f,0,"late");assertEquals(new Receipt(saved),result);
            assertTrue(f.f.mainFault.sql.stream().noneMatch(ProductPromptBodyClearApplicationTest::write));
            assertEquals(original,manifest(f));assertEquals("READY",f.state());
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserve-before","reserve-after","clear-before","clear-after","complete-before","complete-after"})
    void commitUnknownEndsThisCallAndOnlyNewExplicitCallRecovers(String point)throws Exception{
        try(var f=fixture(1)){
            var app=f.application(0,false);boolean reserve=point.startsWith("reserve"),main=point.startsWith("clear"),after=point.endsWith("after");
            var side=main?MAIN:AUTHORITY;String trigger=reserve?"INSERT INTO idp_prompt_cleanup_request":main?
                    "UPDATE conp_revision SET revision_json=NULL":"UPDATE idp_revision_witness SET body_state='CLEARED'";
            f.f.failCommitForSql(side,trigger,after);var continued=new ArrayList<String>();
            java.util.function.Consumer<String> watch=sql->{if(hasCommitFailure(f)&&businessSql(sql))continued.add(sql);};
            f.f.mainFault.afterSql=watch;f.f.authorityFault.afterSql=watch;
            RuntimeException failure=assertThrows(RuntimeException.class,()->clear(app,f,0,"unknown"));
            f.f.mainFault.afterSql=null;f.f.authorityFault.afterSql=null;
            assertTrue(continued.isEmpty(),continued.toString());assertTrue(f.f.fault(side).commits.contains(after?"AFTER_FAILURE":"BEFORE_FAILURE"));
            Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();assertInstanceOf(java.sql.SQLException.class,cause);
            if(reserve||main)assertNoCompletion(f);
            assertEquals(reserve&&!after?0:1,f.count(false,"idp_prompt_cleanup_request"));
            String original=reserve&&!after?null:manifest(f);
            assertEquals(reserve||main&&!after?"PRESENT":"CLEARED",f.bodyState(0));
            assertEquals(!reserve&&!main&&after||reserve&&!after?"READY":"CLEANUP_PENDING",f.state());
            var restarted=f.application(0,true);reset(f);
            Receipt result=reserve||main&&!after?clear(restarted,f,0,"unknown"):lookup(restarted,f,0,"unknown");
            if(original!=null)assertEquals(original,manifest(f));assertEquals("READY",f.state());
            assertEquals(1,f.count(true,"conp_prompt_cleanup_receipt"));assertEquals(result,lookup(f.application(0,true),f,0,"unknown"));
        }
    }
    @Test void committedMainAdmissionFailureStopsBeforeObservationOrCompletion()throws Exception{
        try(var f=fixture(1)){
            var app=f.application(0,false);Path approval=f.f.root.resolve("clear-"+f.versions.getFirst().header().revisionId()+".json");
            byte[] original=Files.readAllBytes(approval);var armed=new AtomicBoolean();var changed=new AtomicBoolean();var continued=new ArrayList<String>();
            f.f.mainFault.afterSql=sql->{
                if(changed.get()&&businessSql(sql))continued.add(sql);
                if(sql.startsWith("INSERT INTO conp_prompt_cleanup_receipt")&&armed.compareAndSet(false,true))
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                        @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED){
                            try{Files.writeString(approval,"{}");changed.set(true);}catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
                        }}
                    });
            };
            assertThrows(RuntimeException.class,()->clear(app,f,0,"postcommit"));f.f.mainFault.afterSql=null;
            assertTrue(changed.get());assertTrue(continued.isEmpty());assertNoCompletion(f);
            assertEquals("CLEARED",f.bodyState(0));assertEquals("CLEANUP_PENDING",f.state());String prepared=manifest(f);
            Files.write(approval,original);assertNotNull(lookup(f.application(0,true),f,0,"postcommit"));assertEquals(prepared,manifest(f));
        }
    }
    @Test void failedReadObservationCannotProduceReadyReceiptOrConsumeCompletion()throws Exception{
        try(var f=fixture(1)){
            Receipt expected=clear(f.application(0,false),f,0,"read-failure");var app=f.application(0,true);reset(f);
            f.f.failCommitForSql(MAIN,"FROM conp_prompt_cleanup_receipt",true);
            assertThrows(RuntimeException.class,()->lookup(app,f,0,"read-failure"));assertNoWrites(f);
            assertTrue(f.f.mainFault.commits.contains("AFTER_FAILURE"));
            assertEquals(expected,lookup(f.application(0,true),f,0,"read-failure"));
        }
    }
    @Test void sameTargetDifferentKeyIsTypedButRepinnedOriginalKeyIsUnavailable()throws Exception{
        try(var f=fixture(1)){
            Receipt expected=clear(f.application(0,false),f,0,"bound");String original=manifest(f);
            var other=f.application(0,true);reset(f);
            assertThrows(ProductPromptBodyClearConflict.class,()->clear(other,f,0,"other"));assertNoWrites(f);
            String ref="clear-"+f.versions.getFirst().header().revisionId()+".json";var value=f.f.read(ref);
            value.put("decisionId",UUID.randomUUID().toString());String sha=f.f.write(ref,value);
            var files=new ProductSourceEvidenceFiles(f.f.root,1048576);
            var registry=new ProductPromptBindingRegistry(files,f.f.bindingsRef,ProductCapturedArchive.digest(Files.readAllBytes(f.f.root.resolve(f.f.bindingsRef))));
            var changed=new ProductPromptBodyClearApproval(files,registry,List.of(new ProductPromptBodyClearApproval.Pin(f.f.workspaceId,f.promptId,
                    f.versions.getFirst().header().revisionId(),ref,sha)),f.f.clock);
            var owner=f.f.newLease();f.reopened.add(owner);var repinned=new ProductPromptBodyClearApplication(changed,owner,f.store,f.reconciliation);reset(f);
            RuntimeException failure=assertThrows(RuntimeException.class,()->lookup(repinned,f,0,"bound"));
            assertFalse(failure instanceof ProductPromptBodyClearConflict);assertNoWrites(f);assertEquals(original,manifest(f));
            assertFalse(failure.getMessage().contains(original));assertFalse(failure.getMessage().contains("Owned text"));
            assertEquals(expected,lookup(f.application(0,true),f,0,"bound"));
        }
    }
    @Test void readyOriginalKeySurvivesLaterClearAndSuccessorWithShorterScope()throws Exception{
        try(var f=fixture(2)){
            Receipt first=clear(f.application(0,false),f,0,"first");String original=manifest(f);
            f.f.advance(Duration.ofSeconds(10));clear(f.application(1,true),f,1,"second");
            assertEquals(first,lookup(f.application(0,true),f,0,"first"));f.append(200,500);
            var latest=f.versions.getLast();assertTrue(latest.header().auditUntil().isBefore(first.closureScopeUntil()));
            var get=f.application(0,true);reset(f);assertEquals(first,lookup(get,f,0,"first"));assertNoWrites(f);
            assertEquals(first,clear(f.application(0,true),f,0,"first"));
            assertEquals(original,f.f.independent.queryForObject("SELECT manifest_json FROM idp_prompt_cleanup_request WHERE request_id=?",String.class,first.requestId()));
            f.f.now=latest.header().auditUntil();var expired=f.application(0,true);reset(f);
            assertThrows(RuntimeException.class,()->lookup(expired,f,0,"first"));assertNoWrites(f);
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void oneSidedCommittedFactsAreUnavailableRatherThanAnOriginalKeyConflict(boolean missingMain)throws Exception{
        try(var f=fixture(1)){
            clear(f.application(0,false),f,0,"one-sided");
            if(missingMain)f.f.db.update("DELETE FROM conp_prompt_cleanup_receipt");
            else f.f.independent.update("DELETE FROM idp_prompt_cleanup_request");
            var app=f.application(0,true);reset(f);
            RuntimeException failure=assertThrows(RuntimeException.class,()->lookup(app,f,0,"one-sided"));
            assertFalse(failure instanceof ProductPromptBodyClearConflict);assertNoWrites(f);
        }
    }
    @Test void currentClosureCutoffAcrossCompletionCommitCannotReturnSuccess()throws Exception{
        try(var f=fixture(2)){
            f.f.advance(Duration.ofSeconds(960));var app=f.application(1,false);var armed=new AtomicBoolean();
            f.f.authorityFault.afterSql=sql->{if(sql.startsWith("UPDATE idp_revision_witness SET body_state='CLEARED'")&&armed.compareAndSet(false,true))
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public int getOrder(){return Integer.MIN_VALUE;}
                    @Override public void beforeCommit(boolean readOnly){f.f.advance(Duration.ofSeconds(10));}
                });};
            assertThrows(RuntimeException.class,()->clear(app,f,1,"scope"));f.f.authorityFault.afterSql=null;
            assertTrue(armed.get());assertEquals("CLEARED",f.bodyState(1));assertEquals("CLEANUP_PENDING",f.state());
            assertEquals("PENDING",f.f.independent.queryForObject("SELECT state FROM idp_prompt_cleanup_request",String.class));
        }
    }
    @ParameterizedTest @ValueSource(strings={"unknown-get","wrong-workspace","wrong-prompt","wrong-revision","empty-key","space-key"})
    void invalidOrMissingRequestCannotStartAPersistentAction(String fault)throws Exception{
        try(var f=fixture(1)){
            var app=f.application(0,false);reset(f);String workspace=fault.equals("wrong-workspace")?UUID.randomUUID().toString():f.f.workspaceId;
            String prompt=fault.equals("wrong-prompt")?UUID.randomUUID().toString():f.promptId;
            String revision=fault.equals("wrong-revision")?UUID.randomUUID().toString():f.versions.getFirst().header().revisionId();
            String key=fault.equals("empty-key")?"":fault.equals("space-key")?" ":"missing";
            assertThrows(RuntimeException.class,()->{if(fault.equals("unknown-get"))app.lookup(f.f.request,workspace,prompt,revision,key);
                else app.clear(f.f.request,workspace,prompt,revision,key);});assertNoWrites(f);
            assertEquals(0,f.count(false,"idp_prompt_cleanup_request"));assertEquals("PRESENT",f.bodyState(0));
        }
    }
    private static Receipt clear(ProductPromptBodyClearApplication app,ProductPromptBodyClearStoreFixture f,int index,String key){
        return app.clear(f.f.request,f.f.workspaceId,f.promptId,f.versions.get(index).header().revisionId(),key);
    }
    private static Receipt lookup(ProductPromptBodyClearApplication app,ProductPromptBodyClearStoreFixture f,int index,String key){
        return app.lookup(f.f.request,f.f.workspaceId,f.promptId,f.versions.get(index).header().revisionId(),key);
    }
    private static String manifest(ProductPromptBodyClearStoreFixture f){return f.f.independent.queryForObject("SELECT manifest_json FROM idp_prompt_cleanup_request",String.class);}
    private static void reset(ProductPromptBodyClearStoreFixture f){f.f.mainFault.afterSql=null;f.f.authorityFault.afterSql=null;
        f.f.mainFault.failSql=null;f.f.authorityFault.failSql=null;f.f.mainFault.clear();f.f.authorityFault.clear();}
    private static boolean write(String sql){return sql.startsWith("INSERT ")||sql.startsWith("UPDATE ")||sql.startsWith("DELETE ");}
    private static void assertNoWrites(ProductPromptBodyClearStoreFixture f){
        assertTrue(f.f.mainFault.sql.stream().noneMatch(ProductPromptBodyClearApplicationTest::write),f.f.mainFault.sql.toString());
        assertTrue(f.f.authorityFault.sql.stream().noneMatch(ProductPromptBodyClearApplicationTest::write),f.f.authorityFault.sql.toString());
    }
    private static void assertNoCompletion(ProductPromptBodyClearStoreFixture f){assertTrue(f.f.authorityFault.sql.stream().noneMatch(sql->
            sql.startsWith("UPDATE idp_revision_witness SET body_state='CLEARED'")||sql.startsWith("UPDATE idp_prompt_control SET state='READY'")
                    ||sql.startsWith("UPDATE idp_prompt_cleanup_request SET state='READY'")),f.f.authorityFault.sql.toString());}
    private static boolean hasCommitFailure(ProductPromptBodyClearStoreFixture f){return java.util.stream.Stream.concat(f.f.mainFault.commits.stream(),f.f.authorityFault.commits.stream())
            .anyMatch(value->value.equals("BEFORE_FAILURE")||value.equals("AFTER_FAILURE"));}
    private static boolean businessSql(String sql){return sql.contains("conp_")||sql.contains("idp_prompt_")||sql.contains("idp_revision_witness");}
}
