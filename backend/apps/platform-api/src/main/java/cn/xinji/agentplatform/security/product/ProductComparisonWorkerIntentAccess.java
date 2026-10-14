package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;

/** Authenticated current intent, not source permission, an execution lease or a submitted result. */
final class ProductComparisonWorkerIntentAccess {
    private final ProductComparisonWorkerTaskAuthority authority;
    private final ProductComparisonWorkerDelegations bindings;
    ProductComparisonWorkerIntentAccess(ProductComparisonWorkerTaskAuthority authority,ProductComparisonWorkerDelegations bindings) {
        this.authority=authority;this.bindings=bindings;
    }
    ProductComparisonWorkerDelegations.Receipt require(HttpServletRequest request,String task,String comparison,
            String candidate,String inputSha,String delegation) {
        var actor=authority.require(request,task,delegation);
        var intent=bindings.currentWorkerIntent(task,comparison,candidate,inputSha,delegation,actor.serviceSubject(),actor.taskVersion());
        authority.recheck(request,task,delegation,actor);
        var after=bindings.currentWorkerIntent(task,comparison,candidate,inputSha,delegation,actor.serviceSubject(),actor.taskVersion());
        if (!intent.equals(after)) throw new IllegalStateException("Comparison worker intent changed");
        authority.recheck(request,task,delegation,actor);
        return intent;
    }
}
