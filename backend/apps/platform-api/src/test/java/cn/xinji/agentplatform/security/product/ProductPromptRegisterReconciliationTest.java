package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRegisterFixture.Side.*;
import static cn.xinji.agentplatform.security.product.ProductPromptRegisterReconciliation.State.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductPromptRegisterReconciliationTest {
    @TempDir Path root;
    ProductComparisonStartMtlsEngineeringSupport.Tls tls;
    @BeforeAll void certificates()throws Exception{tls=new ProductComparisonStartMtlsEngineeringSupport.Tls();}
    @AfterAll void closeCertificates()throws Exception{if(tls!=null)tls.close();}
    ProductPromptRegisterFixture fixture()throws Exception{return new ProductPromptRegisterFixture(root,tls);}

    @Test void allBeforeNeverCreatesCompletionProofOrRebuildsMissingMain()throws Exception{
        try(var f=fixture()){
            f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"all-before");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                var observation=f.reconciliation.observe(lease,request);
                assertThat(observation.state()).isEqualTo(ALL_BEFORE);
                assertThatThrownBy(observation::exactAfter).isInstanceOf(IllegalStateException.class);
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();return null;
            });
            assertThat(f.count(MAIN,"conp_workspace")).isZero();assertThat(f.state()).isEqualTo("PENDING");
        }
    }

    @Test void exactAfterProofIsConsumedOnlyOnceAndAuthorityCompletionIsAtomic()throws Exception{
        try(var f=fixture()){
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"single-use");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                var observation=f.reconciliation.observe(lease,request);
                assertThat(observation.state()).isEqualTo(EXACT_AFTER);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
                var proof=observation.exactAfter();
                assertThat(lease.authority(request.intentUntil(),()->f.requests.complete(lease,request,proof))).isEqualTo(request.receipt());
                return lease.authority(request.intentUntil(),()->f.requests.complete(lease,request,proof));
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.state()).isEqualTo("READY");
            assertThat(f.independent.queryForObject("SELECT control_state FROM idp_workspace",String.class)).isEqualTo("READY");
            assertThat(f.independent.queryForObject("SELECT registry_intent_id FROM idp_workspace",String.class)).isNull();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void proofCannotBeConsumedByAnotherStoreOrChangedOriginalRequest(boolean differentRequest)throws Exception{
        try(var f=fixture()){
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"bound-proof");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                var proof=f.reconciliation.observe(lease,request).exactAfter();
                var differentStore=new ProductPromptRegisterStore(f.store,f.control,f.mapper,f.clock);
                return lease.authority(request.intentUntil(),()->differentRequest
                        ?f.requests.complete(lease,request.ready(),proof):differentStore.complete(lease,request,proof));
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.state()).isEqualTo("PENDING");
            assertThat(f.app.lookup(f.request,f.workspaceId,"bound-proof").key().workspaceId()).isEqualTo(f.workspaceId);
        }
    }

    @Test void proofCannotEscapeItsMaintenanceLeaseOrBeReusedByFreshLease()throws Exception{
        try(var f=fixture()){
            var requestRef=new AtomicReference<ProductPromptRegisterRecords.Request>();
            var proofRef=new AtomicReference<ProductPromptRegisterReconciliation.ExactAfter>();
            f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"lease-bound");requestRef.set(request);
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                proofRef.set(f.reconciliation.observe(lease,request).exactAfter());return null;
            });
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->lease.authority(requestRef.get().intentUntil(),
                    ()->f.requests.complete(lease,requestRef.get(),proofRef.get())))).isInstanceOf(IllegalStateException.class);
            assertThat(f.state()).isEqualTo("PENDING");
            assertThat(f.app.lookup(f.request,f.workspaceId,"lease-bound")).isEqualTo(requestRef.get().receipt());
        }
    }

    @Test void proofCannotCrossThreadsAndRejectedUseDoesNotConsumeIt()throws Exception{
        try(var f=fixture()){
            f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"thread-bound");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                var proof=f.reconciliation.observe(lease,request).exactAfter();
                var failure=new AtomicReference<Throwable>();
                var thread=new Thread(()->{try{proof.consume(f.requests,lease,request);}catch(Throwable denied){failure.set(denied);}});
                thread.start();try{thread.join(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                assertThat(thread.isAlive()).isFalse();assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
                assertThat(lease.authority(request.intentUntil(),()->f.requests.complete(lease,request,proof))).isEqualTo(request.receipt());return null;
            });
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void mainObservationCommitFailureCannotReturnAnyProof(boolean afterRealCommit)throws Exception{
        try(var f=fixture()){
            var returned=new AtomicReference<ProductPromptRegisterReconciliation.Observation>();
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"commit-observation");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                f.failCommitForSql(MAIN,"SELECT valid_until FROM conp_workspace",afterRealCommit);
                returned.set(f.reconciliation.observe(lease,request));return null;
            })).isInstanceOf(RuntimeException.class);
            assertThat(returned.get()).isNull();assertThat(f.state()).isEqualTo("PENDING");
            assertThat(f.count(MAIN,"conp_workspace_register_receipt")).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
        }
    }

    @Test void proofExpiresBeforeAuthorityCompletionAndDoesNotPromotePending()throws Exception{
        try(var f=fixture()){
            assertThatThrownBy(()->f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"expired-proof");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                var proof=f.reconciliation.observe(lease,request).exactAfter();f.advance(Duration.ofSeconds(300));
                return lease.authority(request.intentUntil(),()->f.requests.complete(lease,request,proof));
            })).isInstanceOf(IllegalStateException.class);
            assertThat(f.state()).isEqualTo("PENDING");
        }
    }

    @Test void missingMainReceiptIsUnknownAndNeverProducesProof()throws Exception{
        try(var f=fixture()){
            f.lease.withLease(f.request,f.witness(),lease->{
                var request=f.requests.prepare(lease,"orphan-proof");
                lease.authority(request.intentUntil(),()->f.requests.reserve(lease,request));
                lease.main(request.intentUntil(),()->{f.requests.insert(lease,request);return null;});
                f.db.update("DELETE FROM conp_workspace_register_receipt");
                var observation=f.reconciliation.observe(lease,request);assertThat(observation.state()).isEqualTo(UNKNOWN);
                assertThatThrownBy(observation::exactAfter).isInstanceOf(IllegalStateException.class);return null;
            });
            assertThat(f.state()).isEqualTo("PENDING");assertThat(f.count(MAIN,"conp_workspace")).isEqualTo(1);
        }
    }
}
