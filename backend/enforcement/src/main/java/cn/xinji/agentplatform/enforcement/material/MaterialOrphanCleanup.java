package cn.xinji.agentplatform.enforcement.material;

final class MaterialOrphanCleanup {
    private final MaterialRepository repository;
    private final MaterialFiles files;

    MaterialOrphanCleanup(MaterialRepository repository, MaterialFiles files) {
        this.repository = repository;
        this.files = files;
    }

    int run(Runnable currentJobGrant) {
        int removed = 0;
        for (String key : files.stagedKeys()) {
            currentJobGrant.run();
            if (!repository.fileReferenced(key)) {
                repository.recordCleanup(key, "PENDING");
                try {
                    currentJobGrant.run();
                    files.discard(key);
                    repository.recordCleanup(key, "SUCCEEDED");
                    removed++;
                } catch (RuntimeException failure) {
                    repository.recordCleanup(key, "FAILED");
                    throw failure;
                }
            }
        }
        return removed;
    }
}
