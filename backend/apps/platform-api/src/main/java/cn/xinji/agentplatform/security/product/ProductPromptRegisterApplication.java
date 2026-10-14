package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;

/** Registers an admitted workspace once and reconciles only its original committed facts. */
final class ProductPromptRegisterApplication {
    private final ProductPromptRegisterApproval approval;
    private final ProductPromptRegistrationLease leases;
    private final ProductPromptRegisterStore store;
    private final ProductPromptRegisterReconciliation reconciliation;

    ProductPromptRegisterApplication(ProductPromptRegisterApproval approval,ProductPromptRegistrationLease leases,
            ProductPromptRegisterStore store,ProductPromptRegisterReconciliation reconciliation){
        this.approval=Objects.requireNonNull(approval);this.leases=Objects.requireNonNull(leases);
        this.store=Objects.requireNonNull(store);this.reconciliation=Objects.requireNonNull(reconciliation);
    }

    ProductPromptRegisterRecords.Receipt register(HttpServletRequest request,String workspaceId,String key){
        var witness=approval.admit(workspaceId);
        return leases.withLease(request,witness,lease->{
            var coordinates=store.key(lease,key);
            var original=lease.authority(witness.expiresAt(),()->store.find(lease,coordinates));
            if(original!=null)return settle(lease,original);
            lease.main(witness.expiresAt(),()->{store.requireMainAbsent(lease,coordinates);return null;});
            var prepared=store.prepare(lease,key);
            var reserved=lease.authority(prepared.intentUntil(),()->store.reserve(lease,prepared));
            // A main commit exception is UNKNOWN; reconciliation requires another explicit original-key call.
            lease.main(reserved.intentUntil(),()->{store.insert(lease,reserved);return null;});
            return settle(lease,reserved);
        });
    }

    ProductPromptRegisterRecords.Receipt lookup(HttpServletRequest request,String workspaceId,String key){
        var witness=approval.admit(workspaceId);
        return leases.withLease(request,witness,lease->{
            var coordinates=store.key(lease,key);
            var original=lease.authority(witness.expiresAt(),()->store.find(lease,coordinates));
            if(original==null)throw unavailable();
            return settle(lease,original);
        });
    }

    private ProductPromptRegisterRecords.Receipt settle(ProductPromptRegistrationLease.Lease lease,
            ProductPromptRegisterRecords.Request original){
        if(!original.requestSha().equals(store.requestSha(lease)))throw new ProductPromptRegisterConflict();
        var observation=reconciliation.observe(lease,original);
        if(observation.state()!=ProductPromptRegisterReconciliation.State.EXACT_AFTER)throw unavailable();
        Instant cutoff="READY".equals(original.state())?original.receiptUntil():original.intentUntil();
        return lease.authority(cutoff,()->store.complete(lease,original,observation.exactAfter()));
    }
}
