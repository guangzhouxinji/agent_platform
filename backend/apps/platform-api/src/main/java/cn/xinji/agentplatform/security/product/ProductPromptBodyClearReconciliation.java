package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.unavailable;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearStore.MainObservation;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** One naturally committed main observation, then one exact authority completion. */
final class ProductPromptBodyClearReconciliation {
    enum State { ALL_BEFORE,EXACT_AFTER,UNKNOWN }
    static final class Observation {
        private final State state;private final ExactAfter proof;
        private Observation(State state,ExactAfter proof){this.state=state;this.proof=proof;}
        State state(){return state;}
        ExactAfter exactAfter(){if(state!=State.EXACT_AFTER||proof==null)throw unavailable();return proof;}
    }
    static final class ExactAfter {
        private final ProductPromptBodyClearReconciliation owner;
        private final Lease lease;
        private final Request request;
        private final MainObservation observed;
        private final Instant cutoff;
        private final Thread thread=Thread.currentThread();
        private boolean consumed;
        private ExactAfter(ProductPromptBodyClearReconciliation owner,Lease lease,Request request,MainObservation observed,Instant cutoff){
            this.owner=owner;this.lease=lease;this.request=request;this.observed=observed;this.cutoff=cutoff;
        }
        Instant cutoff(){return cutoff;}
        MainObservation consume(ProductPromptBodyClearStore expectedStore,Lease expectedLease,Request expectedRequest){
            if(consumed||thread!=Thread.currentThread()||owner.store!=expectedStore||lease!=expectedLease||!request.equals(expectedRequest))throw unavailable();
            expectedStore.requireAuthorityTransaction(lease);expectedStore.requireApproved(lease,request);
            owner.current(cutoff);lease.deadline.remaining(cutoff);consumed=true;return observed;
        }
    }
    private final ProductPromptBodyClearStore store;
    private final Clock clock;
    ProductPromptBodyClearReconciliation(ProductPromptBodyClearStore store,Clock clock){this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);}
    Observation observe(Lease lease,Request request){
        ProductPromptRegistrationLease.outside();store.requireApproved(lease,request);
        Instant initial=store.settlementUntil(lease,request,request.closureScopeUntil());current(initial);
        Instant scope=lease.main(initial,()->store.readCurrentScope(lease,request));
        Instant cutoff=store.settlementUntil(lease,request,scope);current(cutoff);
        MainObservation observed=lease.main(cutoff,()->{
            MainObservation value=store.observeMain(lease,request);
            if(value.state()!=State.UNKNOWN&&!scope.equals(value.scope()))throw unavailable();return value;
        });
        // The worker transaction has committed and released all Spring resources at this point.
        ProductPromptRegistrationLease.outside();lease.requireCurrent();store.requireApproved(lease,request);current(cutoff);
        return new Observation(observed.state(),observed.state()==State.EXACT_AFTER?new ExactAfter(this,lease,request,observed,cutoff):null);
    }
    private void current(Instant cutoff){if(!clock.instant().isBefore(cutoff))throw unavailable();}
}
