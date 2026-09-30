package cn.xinji.agentplatform.security.product;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal product entry: never accepts a caller-supplied worker identity. */
final class ProductTaskWorkerExecution {
    private final ProductWorkerAccess access;
    private final ProductTaskExecutionFence fence;
    private final TransactionTemplate transactions;

    ProductTaskWorkerExecution(ProductWorkerAccess access, ProductTaskExecutionFence fence,
            TransactionTemplate transactions) {
        this.access = access;
        this.fence = fence;
        this.transactions = transactions;
    }

    ProductTaskExecutionFence.Lease acquire(HttpServletRequest request, String taskId,
            String inputId, String delegationId, Duration duration) {
        var lease = transactions.execute(status -> {
            var worker = access.require(request, taskId, inputId, delegationId);
            ProductTaskExecutionFence.Lease acquired;
            try {
                acquired = fence.acquire(taskId, inputId, worker.taskVersion(),
                        worker.serviceSubject(), duration);
            } catch (ProductTaskExecutionFence.ReconciliationRequired pending) {
                access.require(request, taskId, inputId, delegationId);
                return null;
            }
            access.require(request, taskId, inputId, delegationId);
            return acquired;
        });
        if (lease == null) throw new ProductTaskExecutionFence.ReconciliationRequired();
        return lease;
    }

    void checkpoint(HttpServletRequest request, String taskId, String inputId, String delegationId,
            ProductTaskExecutionFence.Lease lease, long sequence, String resultRef, String resultSha) {
        transactions.executeWithoutResult(status -> {
            var worker = access.require(request, taskId, inputId, delegationId);
            requireOwner(taskId, inputId, lease, worker);
            fence.checkpoint(taskId, inputId, lease, sequence, resultRef, resultSha);
            access.require(request, taskId, inputId, delegationId);
        });
    }

    ProductTaskExecutionFence.Lease reconcile(HttpServletRequest request, String taskId,
            String inputId, String delegationId, Duration duration) {
        return transactions.execute(status -> {
            var lease = fence.reconcileAuthorized(taskId, inputId, delegationId, access, request, duration);
            access.require(request, taskId, inputId, delegationId);
            return lease;
        });
    }

    void publish(HttpServletRequest request, String taskId, String inputId, String delegationId,
            ProductTaskExecutionFence.Lease lease, Runnable sameDatabaseResultCommit) {
        transactions.executeWithoutResult(status -> {
            var worker = access.require(request, taskId, inputId, delegationId);
            requireOwner(taskId, inputId, lease, worker);
            fence.publish(taskId, inputId, lease, sameDatabaseResultCommit);
            access.require(request, taskId, inputId, delegationId);
        });
    }

    private void requireOwner(String taskId, String inputId, ProductTaskExecutionFence.Lease lease,
            ProductWorkerAccess.Authorized worker) {
        if (lease == null || !worker.taskId().equals(taskId) || !worker.inputId().equals(inputId))
            throw new IllegalStateException("Execution lease does not belong to worker input");
        fence.assertOwner(taskId, lease, worker.serviceSubject());
    }
}
