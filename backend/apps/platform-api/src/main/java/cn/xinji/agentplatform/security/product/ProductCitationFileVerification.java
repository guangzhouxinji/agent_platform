package cn.xinji.agentplatform.security.product;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutionException;

/** All original bytes must pass; bounded read-only transport never grants source access. */
final class ProductCitationFileVerification {
    record Reference(String path, String sha) { }
    private static final Semaphore APPLICATION_READS = new Semaphore(8);
    private ProductCitationFileVerification() { }
    static void verify(ProductSourceEvidenceFiles files, List<Reference> references) {
        if (references.size() > 100) throw new IllegalStateException("Citation page exceeds contract");
        if (references.isEmpty()) return;
        var pageReads = new Semaphore(4);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (var reference : references) tasks.add(executor.submit(() -> {
                pageReads.acquire();
                try {
                    APPLICATION_READS.acquire();
                    try { files.verified(reference.path(), reference.sha()); }
                    finally { APPLICATION_READS.release(); }
                } finally { pageReads.release(); }
                return null;
            }));
            for (var task : tasks) task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Citation verification interrupted", interrupted);
        } catch (ExecutionException unavailable) {
            throw new IllegalStateException("Citation evidence unavailable", unavailable.getCause());
        }
    }
}
