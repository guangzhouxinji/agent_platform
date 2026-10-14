package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptRegisterRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only a naturally settled main observation can produce a single-use completion proof. */
final class ProductPromptRegisterReconciliation {
    enum State { ALL_BEFORE,EXACT_AFTER,UNKNOWN }
    static final class Observation {
        private final State state;
        private final ExactAfter proof;
        private Observation(State state,ExactAfter proof){this.state=state;this.proof=proof;}
        State state(){return state;}
        ExactAfter exactAfter(){if(state!=State.EXACT_AFTER||proof==null)throw unavailable();return proof;}
    }
    static final class ExactAfter {
        private final ProductPromptRegisterReconciliation owner;
        private final Lease lease;
        private final Request request;
        private final Instant cutoff;
        private final Thread thread=Thread.currentThread();
        private boolean consumed;
        private ExactAfter(ProductPromptRegisterReconciliation owner,Lease lease,Request request,Instant cutoff){
            this.owner=owner;this.lease=lease;this.request=request;this.cutoff=cutoff;
        }
        void consume(ProductPromptRegisterStore expectedStore,Lease expectedLease,Request expectedRequest){
            if(consumed||thread!=Thread.currentThread()||owner.store!=expectedStore||lease!=expectedLease||!request.equals(expectedRequest))throw unavailable();
            expectedStore.requireAuthorityTransaction(lease);lease.requireCurrent();owner.current(cutoff);
            expectedStore.requireApproved(lease,request);consumed=true;
        }
    }
    private final ProductPromptRegisterStore store;
    private final Clock clock;
    ProductPromptRegisterReconciliation(ProductPromptRegisterStore store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    Observation observe(Lease lease,Request request){
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw unavailable();
        store.requireApproved(lease,request);
        Instant cutoff=min(request.receiptUntil(),request.target().validUntil(),lease.approval().expiresAt());
        if("PENDING".equals(request.state()))cutoff=min(cutoff,request.intentUntil());
        current(cutoff);State state=lease.main(cutoff,()->store.observeMain(lease,request));
        // main() has returned after actual transaction settlement, never merely a callback return.
        if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()
                ||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw unavailable();
        lease.requireCurrent();store.requireApproved(lease,request);current(cutoff);
        return new Observation(state,state==State.EXACT_AFTER?new ExactAfter(this,lease,request,cutoff):null);
    }
    private void current(Instant cutoff){if(!clock.instant().isBefore(cutoff))throw unavailable();}
    private static Instant min(Instant...values){return java.util.Arrays.stream(values).min(Instant::compareTo).orElseThrow();}
}
