package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.unavailable;
import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearStore.min;

import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Receipt;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearReconciliation.State;
import cn.xinji.agentplatform.security.product.ProductPromptRegistrationLease.Lease;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;

/** One admitted maintenance call; uncertain commits require a new original-key call. */
final class ProductPromptBodyClearApplication {
    private final ProductPromptBodyClearApproval approval;
    private final ProductPromptRegistrationLease leases;
    private final ProductPromptBodyClearStore store;
    private final ProductPromptBodyClearReconciliation reconciliation;

    ProductPromptBodyClearApplication(ProductPromptBodyClearApproval approval,ProductPromptRegistrationLease leases,
            ProductPromptBodyClearStore store,ProductPromptBodyClearReconciliation reconciliation){
        this.approval=Objects.requireNonNull(approval);this.leases=Objects.requireNonNull(leases);
        this.store=Objects.requireNonNull(store);this.reconciliation=Objects.requireNonNull(reconciliation);
    }

    Receipt clear(HttpServletRequest request,String workspaceId,String promptId,String revisionId,String rawKey){
        return execute(request,workspaceId,promptId,revisionId,rawKey,true);
    }

    Receipt lookup(HttpServletRequest request,String workspaceId,String promptId,String revisionId,String rawKey){
        return execute(request,workspaceId,promptId,revisionId,rawKey,false);
    }

    private Receipt execute(HttpServletRequest request,String workspaceId,String promptId,String revisionId,
            String rawKey,boolean mayClear){
        var witness=approval.admit(workspaceId,promptId,revisionId);
        return leases.withLease(request,witness,lease->{
            var key=store.key(lease,rawKey);
            Instant initial=min(lease.expiresAt(),witness.expiresAt(),witness.target().auditUntil());
            Request original=lease.authority(initial,()->store.find(lease,key));
            if(original==null){
                if(!mayClear)throw unavailable();
                Request prepared=lease.main(initial,()->store.prepare(lease,key));
                Request reserved=lease.authority(store.mutationUntil(lease,prepared),()->store.reserve(lease,prepared));
                clearMain(lease,reserved);
                return settle(lease,reserved,false);
            }
            store.requireApproved(lease,original);
            return settle(lease,original,mayClear);
        });
    }

    private Receipt settle(Lease lease,Request original,boolean mayClear){
        var observation=reconciliation.observe(lease,original);
        if(observation.state()==State.ALL_BEFORE){
            if(!mayClear||!"PENDING".equals(original.state()))throw unavailable();
            clearMain(lease,original);
            observation=reconciliation.observe(lease,original);
        }
        if(observation.state()!=State.EXACT_AFTER)throw unavailable();
        var proof=observation.exactAfter();
        return lease.authority(proof.cutoff(),()->store.complete(lease,original,proof));
    }

    private void clearMain(Lease lease,Request original){
        lease.main(store.mutationUntil(lease,original),()->{store.clear(lease,original);return null;});
    }
}
