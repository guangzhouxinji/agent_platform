package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearReconciliation.State;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** True two-H2 transaction failures with owned TLS material; no online installation or deletion. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptBodyClearStoreTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{tls.close();}
    ProductPromptBodyClearStoreFixture fixture(int count)throws Exception{return new ProductPromptBodyClearStoreFixture(root.resolve(UUID.randomUUID().toString()),tls,count);}

    @Test void exactConversionRemovesWholeJsonAndKeepsOriginalBusinessFacts()throws Exception {
        try(var f=fixture(2)) {
            var events=f.f.snapshot(MAIN,"conp_event");var receipts=f.f.snapshot(MAIN,"conp_receipt");
            var original=f.versions.getFirst();var saved=new AtomicReference<Request>();
            f.with(0,lease->{
                Request request=f.prepare(lease,"clear-original");saved.set(request);f.reserve(lease,request);
                assertEquals("CLEANUP_PENDING",f.state());f.clear(lease,request);
                assertEquals("CLEARED",f.bodyState(0));assertEquals("PRESENT",f.bodyState(1));
                assertNull(f.f.db.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,original.header().revisionId()));
                assertEquals(request.receipt(),f.settle(lease,request));return null;
            });
            assertEquals(events,f.f.snapshot(MAIN,"conp_event"));assertEquals(receipts,f.f.snapshot(MAIN,"conp_receipt"));
            assertEquals("READY",f.state());assertEquals(1,f.count(true,"conp_prompt_cleanup_receipt"));
            assertThrows(RuntimeException.class,()->new TransactionTemplate(f.f.mainManager).execute(status->
                    f.f.store.readRevision(f.promptId,original.header().revisionId(),f.f.control)));
            assertEquals(f.versions.get(1).write().revision(),new TransactionTemplate(f.f.mainManager).execute(status->
                    f.f.store.readRevision(f.promptId,f.versions.get(1).header().revisionId(),f.f.control)));
            f.with(0,lease->{var ready=f.find(lease,"clear-original");assertEquals(saved.get().ready(),ready);
                assertEquals(saved.get().receipt(),f.settle(lease,ready));return null;});
        }
    }
    @Test void observationOfAllBeforeDoesNotWriteAndLateExactAfterSettlesWithoutOldIntent()throws Exception {
        try(var f=fixture(1)) {
            var saved=new AtomicReference<Request>();
            f.with(0,lease->{Request request=f.prepare(lease,"late");saved.set(request);f.reserve(lease,request);
                var before=f.reconciliation.observe(lease,request);assertEquals(State.ALL_BEFORE,before.state());
                assertThrows(IllegalStateException.class,before::exactAfter);assertEquals("PRESENT",f.bodyState(0));
                assertEquals(0,f.count(true,"conp_prompt_cleanup_receipt"));f.clear(lease,request);return null;});
            f.f.advance(Duration.ofSeconds(301));
            f.with(0,lease->{Request original=f.find(lease,"late");assertEquals(saved.get(),original);
                assertEquals(original.receipt(),f.settle(lease,original));return null;});
            assertEquals(1,f.count(true,"conp_prompt_cleanup_receipt"));
        }
    }
    @Test void expiredAllBeforeCannotContinueOrRefreshPrepared()throws Exception {
        try(var f=fixture(1)) {
            f.with(0,lease->{Request request=f.prepare(lease,"expired-mutation");f.reserve(lease,request);return null;});
            var before=f.f.snapshot(AUTHORITY,"idp_prompt_cleanup_request");f.f.advance(Duration.ofSeconds(300));
            f.with(0,lease->{Request request=f.find(lease,"expired-mutation");assertEquals(State.ALL_BEFORE,f.reconciliation.observe(lease,request).state());
                assertThrows(RuntimeException.class,()->f.clear(lease,request));return null;});
            assertEquals(before,f.f.snapshot(AUTHORITY,"idp_prompt_cleanup_request"));assertEquals("PRESENT",f.bodyState(0));
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserve-insert","reserve-control","clear-body","clear-head","clear-receipt","complete-witness","complete-control","complete-request"})
    void caughtPhysicalWriteFailureCannotCommitHalfOfThePrimitive(String point)throws Exception {
        try(var f=fixture(1)) {
            var request=new AtomicReference<Request>();boolean reserve=point.startsWith("reserve"),clear=point.startsWith("clear");
            assertThrows(RuntimeException.class,()->f.with(0,lease->{
                Request prepared=f.prepare(lease,"caught");request.set(prepared);
                if(!reserve)f.reserve(lease,prepared);if(!reserve&&!clear)f.clear(lease,prepared);
                var source=clear?f.f.mainFault:f.f.authorityFault;
                source.failSql=sql->sql.startsWith(failedSql(point));
                if(reserve)lease.authority(f.store.mutationUntil(lease,prepared),()->{
                    assertThrows(RuntimeException.class,()->f.store.reserve(lease,prepared));return null;});
                else if(clear)lease.main(f.store.mutationUntil(lease,prepared),()->{
                    assertThrows(RuntimeException.class,()->f.store.clear(lease,prepared));return null;});
                else {var proof=f.reconciliation.observe(lease,prepared).exactAfter();lease.authority(proof.cutoff(),()->{
                    assertThrows(RuntimeException.class,()->f.store.complete(lease,prepared,proof));return null;});}
                return null;
            }));
            assertEquals(reserve?"READY":"CLEANUP_PENDING",f.state());
            assertEquals(reserve?0:1,f.count(false,"idp_prompt_cleanup_request"));
            assertEquals(!reserve&&!clear?"CLEARED":"PRESENT",f.bodyState(0));
            assertEquals(!reserve&&!clear?1:0,f.count(true,"conp_prompt_cleanup_receipt"));
            assertEquals("PRESENT",f.f.independent.queryForObject("SELECT body_state FROM idp_revision_witness",String.class));
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserve-before","reserve-after","clear-before","clear-after","complete-before","complete-after"})
    void unknownCommitPreservesObservedFactsAndRequiresAnotherOriginalKeyCall(String point)throws Exception {
        try(var f=fixture(1)) {
            var saved=new AtomicReference<Request>();boolean reserve=point.startsWith("reserve"),clear=point.startsWith("clear"),after=point.endsWith("after");
            RuntimeException failure=assertThrows(RuntimeException.class,()->f.with(0,lease->{
                Request request=f.prepare(lease,"unknown");saved.set(request);
                if(!reserve)f.reserve(lease,request);if(!reserve&&!clear)f.clear(lease,request);
                f.f.failCommitForSql(clear?MAIN:AUTHORITY,reserve?"INSERT INTO idp_prompt_cleanup_request":clear?
                        "UPDATE conp_revision SET revision_json=NULL":"UPDATE idp_revision_witness SET body_state='CLEARED'",after);
                if(reserve)f.reserve(lease,request);else if(clear)f.clear(lease,request);else f.settle(lease,request);return null;
            }));
            Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();assertTrue(cause instanceof java.sql.SQLException);
            assertEquals(reserve&&!after?"READY":!reserve&&!clear&&after?"READY":"CLEANUP_PENDING",f.state());
            assertEquals(reserve||clear&&!after?"PRESENT":"CLEARED",f.bodyState(0));
            assertEquals(reserve&&!after?0:1,f.count(false,"idp_prompt_cleanup_request"));
            if(!reserve&&(after||!clear))f.withFresh(0,lease->{Request original=f.find(lease,"unknown");
                assertEquals(saved.get().manifest(),original.manifest());assertEquals(saved.get().receipt(),f.settle(lease,original));return null;});
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserve","clear","complete"})
    void caughtErrorAfterAnActualWriteStillMarksTheWholePrimitiveRollbackOnly(String phase)throws Exception {
        try(var f=fixture(1)) {
            boolean reserve=phase.equals("reserve"),clear=phase.equals("clear");
            assertThrows(RuntimeException.class,()->f.with(0,lease->{
                Request request=f.prepare(lease,"caught-error");if(!reserve)f.reserve(lease,request);if(!reserve&&!clear)f.clear(lease,request);
                var source=clear?f.f.mainFault:f.f.authorityFault;var armed=new AtomicBoolean();
                String trigger=reserve?"INSERT INTO idp_prompt_cleanup_request":clear?"UPDATE conp_revision SET revision_json=NULL":"UPDATE idp_revision_witness SET body_state='CLEARED'";
                source.afterSql=sql->{if(sql.startsWith(trigger)&&armed.compareAndSet(false,true))throw new AssertionError("Owned after-write fault");};
                if(reserve)lease.authority(f.store.mutationUntil(lease,request),()->{
                    assertThrows(AssertionError.class,()->f.store.reserve(lease,request));return null;});
                else if(clear)lease.main(f.store.mutationUntil(lease,request),()->{
                    assertThrows(AssertionError.class,()->f.store.clear(lease,request));return null;});
                else {var proof=f.reconciliation.observe(lease,request).exactAfter();lease.authority(proof.cutoff(),()->{
                    assertThrows(AssertionError.class,()->f.store.complete(lease,request,proof));return null;});}return null;
            }));
            assertEquals(reserve?"READY":"CLEANUP_PENDING",f.state());
            assertEquals(reserve?0:1,f.count(false,"idp_prompt_cleanup_request"));
            assertEquals(!reserve&&!clear?"CLEARED":"PRESENT",f.bodyState(0));
            assertEquals("PRESENT",f.f.independent.queryForObject("SELECT body_state FROM idp_revision_witness",String.class));
        }
    }
    @ParameterizedTest @ValueSource(strings={"reserve","clear","complete"})
    void earliestClosureAuditAcrossActualBeforeCommitRefusesSuccess(String phase)throws Exception {
        try(var f=fixture(2)) {
            f.f.advance(Duration.ofSeconds(960));
            assertThrows(RuntimeException.class,()->f.with(1,lease->{
                Request request=f.prepare(lease,"scope-expiry");
                if(!phase.equals("reserve"))f.reserve(lease,request);if(phase.equals("complete"))f.clear(lease,request);
                var source=phase.equals("clear")?f.f.mainFault:f.f.authorityFault;
                String trigger=phase.equals("reserve")?"INSERT INTO idp_prompt_cleanup_request":phase.equals("clear")?
                        "UPDATE conp_revision SET revision_json=NULL":"UPDATE idp_revision_witness SET body_state='CLEARED'";
                var armed=new AtomicBoolean();source.afterSql=sql->{if(sql.startsWith(trigger)&&armed.compareAndSet(false,true))expireBeforeCommit(f,10);};
                if(phase.equals("reserve"))f.reserve(lease,request);else if(phase.equals("clear"))f.clear(lease,request);else f.settle(lease,request);
                return null;
            }));
            assertEquals(phase.equals("reserve")?"READY":"CLEANUP_PENDING",f.state());
            assertEquals(phase.equals("complete")?"CLEARED":"PRESENT",f.bodyState(1));
        }
    }
    @Test void sameGenerationLaterClearsAndSuccessorGenerationKeepOldMaintenanceReceiptUsable()throws Exception {
        try(var f=fixture(2)) {
            var first=new AtomicReference<Request>();
            f.with(0,lease->{Request request=f.prepare(lease,"first");first.set(request);f.reserve(lease,request);f.clear(lease,request);f.settle(lease,request);return null;});
            f.f.advance(Duration.ofSeconds(10));
            f.with(1,lease->{Request request=f.prepare(lease,"second");f.reserve(lease,request);f.clear(lease,request);f.settle(lease,request);return null;});
            f.with(0,lease->{assertEquals(first.get().receipt(),f.settle(lease,f.find(lease,"first")));return null;});
            f.append(200,500);
            f.with(0,lease->{assertEquals(first.get().receipt(),f.settle(lease,f.find(lease,"first")));return null;});
            assertEquals(2,f.count(true,"conp_prompt_cleanup_receipt"));assertEquals(3,f.versions.size());
        }
    }
    @Test void ordinarySaveBetweenCommittedObservationAndCompletionRefusesStaleProof()throws Exception {
        try(var f=fixture(2)) {
            assertThrows(RuntimeException.class,()->f.with(0,lease->{Request request=f.prepare(lease,"race");f.reserve(lease,request);f.clear(lease,request);f.settle(lease,request);
                Request ready=f.find(lease,"race");var proof=f.reconciliation.observe(lease,ready).exactAfter();f.append(100,700);
                assertThrows(RuntimeException.class,()->lease.authority(proof.cutoff(),()->f.store.complete(lease,ready,proof)));return null;}));
            f.withFresh(0,lease->{Request ready=f.find(lease,"race");assertEquals(ready.receipt(),f.settle(lease,ready));return null;});
        }
    }
    @Test void proofIsThreadLeaseStoreRequestBoundAndCannotBeConsumedTwice()throws Exception {
        try(var f=fixture(1);var worker=Executors.newSingleThreadExecutor()) {
            var escaped=new AtomicReference<Lease>();f.with(0,lease->{escaped.set(lease);return null;});
            f.with(0,lease->{Request request=f.prepare(lease,"proof");f.reserve(lease,request);f.clear(lease,request);
                var proof=f.reconciliation.observe(lease,request).exactAfter();
                try{worker.submit(()->assertThrows(IllegalStateException.class,()->proof.consume(f.store,lease,request))).get(5,TimeUnit.SECONDS);}
                catch(Exception failed){throw new AssertionError(failed);}
                lease.authority(proof.cutoff(),()->{
                    assertThrows(IllegalStateException.class,()->proof.consume(f.store,escaped.get(),request));
                    assertThrows(IllegalStateException.class,()->proof.consume(new ProductPromptBodyClearStore(f.f.store,f.f.control,f.f.mapper,f.f.clock),lease,request));
                    assertThrows(IllegalStateException.class,()->proof.consume(f.store,lease,request.ready()));
                    assertEquals(request.afterHead(),proof.consume(f.store,lease,request).head());
                    assertThrows(IllegalStateException.class,()->proof.consume(f.store,lease,request));return null;
                });
                f.settle(lease,request);return null;
            });
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void scalarDriftIsRejectedBeforeProofIncludingSuccessorGeneration(boolean successor)throws Exception {
        try(var f=fixture(1)) {
            f.with(0,lease->{Request request=f.prepare(lease,"tamper");f.reserve(lease,request);f.clear(lease,request);f.settle(lease,request);return null;});
            if(successor)f.append(100,700);
            f.f.db.update("UPDATE conp_event SET request_sha=? WHERE prompt_id=?","e".repeat(64),f.promptId);
            assertThrows(RuntimeException.class,()->f.with(0,lease->{f.reconciliation.observe(lease,f.find(lease,"tamper"));return null;}));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rowReadersRejectExpiryDuringManifestRead(boolean main)throws Exception {
        try(var f=fixture(1)) {
            var saved=new AtomicReference<Request>();f.with(0,lease->{Request request=f.prepare(lease,"read-expiry");saved.set(request);
                f.reserve(lease,request);f.clear(lease,request);f.settle(lease,request);return null;});
            var source=main?f.f.mainFault:f.f.authorityFault;var triggered=new AtomicBoolean();
            source.afterSql=sql->{if(sql.startsWith("SELECT request_id,receipt_id,operation")&&triggered.compareAndSet(false,true))f.f.now=saved.get().receiptUntil();};
            assertThrows(RuntimeException.class,()->f.with(0,lease->{
                java.util.function.Supplier<Void> read=()->{assertThrows(IllegalStateException.class,()->{
                    if(main)f.store.rows.mainReceipt(f.promptId,saved.get().target().revisionId());
                    else f.store.rows.authorityRequest(f.promptId,saved.get().target().revisionId(),false);
                });return null;};
                if(main)lease.main(lease.expiresAt(),read);else lease.authority(lease.expiresAt(),read);return null;
            }));assertTrue(triggered.get());
        }
    }
    @Test void oversizedManifestFailsBeforeClobReadAndOriginalKeyCannotChange()throws Exception {
        try(var f=fixture(1)) {
            f.with(0,lease->{Request request=f.prepare(lease,"original");f.reserve(lease,request);return null;});
            assertThrows(RuntimeException.class,()->f.with(0,lease->{f.find(lease,"different");return null;}));
            f.f.independent.update("UPDATE idp_prompt_cleanup_request SET manifest_json=?","x".repeat(17000));f.f.authorityFault.clear();
            assertThrows(RuntimeException.class,()->f.with(0,lease->{f.find(lease,"original");return null;}));
            assertTrue(f.f.authorityFault.sql.stream().noneMatch(sql->sql.startsWith("SELECT request_id,receipt_id,operation")));
        }
    }
    @Test void mainMaintenancePhasesNeverAcquireAuthoritySpringReaders()throws Exception {
        try(var f=fixture(1)) {
            f.with(0,lease->{
                Request request=lease.main(lease.expiresAt(),()->{assertEquals(1,TransactionSynchronizationManager.getResourceMap().size());
                    int before=f.f.authorityFault.sql.size();Request value=f.store.prepare(lease,f.store.key(lease,"single-resource"));
                    assertOnlyMaintainerReads(f,before);assertEquals(1,TransactionSynchronizationManager.getResourceMap().size());return value;});
                f.reserve(lease,request);
                lease.main(f.store.mutationUntil(lease,request),()->{int before=f.f.authorityFault.sql.size();f.store.clear(lease,request);
                    assertOnlyMaintainerReads(f,before);assertEquals(1,TransactionSynchronizationManager.getResourceMap().size());return null;});
                f.settle(lease,request);assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());return null;
            });
        }
    }
    private static void assertOnlyMaintainerReads(ProductPromptBodyClearStoreFixture f,int before){
        assertTrue(f.f.authorityFault.sql.subList(before,f.f.authorityFault.sql.size()).stream().allMatch(sql->sql.contains("FROM id2_maintainer")));
    }
    private static void expireBeforeCommit(ProductPromptBodyClearStoreFixture f,long seconds){
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public int getOrder(){return Integer.MIN_VALUE;}
            @Override public void beforeCommit(boolean readOnly){f.f.advance(Duration.ofSeconds(seconds));}
        });
    }
    private static String failedSql(String point){return switch(point){
        case "reserve-insert"->"INSERT INTO idp_prompt_cleanup_request";case "reserve-control"->"UPDATE idp_prompt_control SET state='CLEANUP_PENDING'";
        case "clear-body"->"UPDATE conp_revision SET revision_json=NULL";case "clear-head"->"UPDATE conp_prompt SET scalar_sha";
        case "clear-receipt"->"INSERT INTO conp_prompt_cleanup_receipt";case "complete-witness"->"UPDATE idp_revision_witness SET body_state='CLEARED'";
        case "complete-control"->"UPDATE idp_prompt_control SET state='READY'";case "complete-request"->"UPDATE idp_prompt_cleanup_request SET state='READY'";
        default->throw new IllegalArgumentException(point);
    };}
}
