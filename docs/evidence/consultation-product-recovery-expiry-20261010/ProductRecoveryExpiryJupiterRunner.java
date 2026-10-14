import java.util.*;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.jupiter.engine.JupiterTestEngine;

/** Standalone Jupiter engine, no Maven, launcher download, shared target writes, or DB fallback. */
public final class ProductRecoveryExpiryJupiterRunner {
    public static void main(String[] args) {
        String expectedGuard = args.length == 1 ? args[0] : null;
        ConfigurationParameters parameters = new ConfigurationParameters() {
            public Optional<String> get(String key) { return Optional.empty(); }
            public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
            public int size() { return 0; }
            public Set<String> keySet() { return Set.of(); }
        };
        var selector = DiscoverySelectors.selectClass("cn.xinji.agentplatform.security.product.ProductTaskBackupExpiryTest");
        EngineDiscoveryRequest discovery = new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
                return type.isInstance(selector) ? List.of(type.cast(selector)) : List.of();
            }
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) { return List.of(); }
            public ConfigurationParameters getConfigurationParameters() { return parameters; }
        };
        var engine = new JupiterTestEngine();
        var root = engine.discover(discovery, UniqueId.forEngine(engine.getId()));
        int[] counts = new int[4]; // successful tests, failed descriptors, skipped tests, observed expected guard
        engine.execute(new ExecutionRequest(root, new EngineExecutionListener() {
            public void executionSkipped(TestDescriptor descriptor, String reason) {
                if (descriptor.isTest()) counts[2]++;
                System.out.println("SKIPPED " + descriptor.getDisplayName() + ": " + reason);
            }
            public void executionFinished(TestDescriptor descriptor, TestExecutionResult result) {
                if (result.getStatus() == TestExecutionResult.Status.SUCCESSFUL && descriptor.isTest()) {
                    counts[0]++; System.out.println("PASSED " + descriptor.getDisplayName());
                }
                if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                    counts[1]++;
                    var failure = result.getThrowable().orElseThrow();
                    System.out.println("FAILED " + descriptor.getDisplayName() + ": " + failure.getClass().getName());
                    if (expectedGuard != null && failure.toString().contains(expectedGuard)) counts[3]++;
                    else if (expectedGuard == null) failure.printStackTrace(System.out);
                }
            }
        }, parameters));
        System.out.printf("successfulTests=%d failedDescriptors=%d skippedTests=%d expectedGuard=%d mode=%s%n",
                counts[0], counts[1], counts[2], counts[3], expectedGuard == null ? "SELF_AUTHORED_FILES_NO_DB_OR_OLD_PACKAGE" : "OFFLINE_GUARD_NOT_DM8");
        boolean ok = expectedGuard == null ? counts[0] == 13 && counts[1] == 0 && counts[2] == 0
                : counts[0] == 0 && counts[1] == 1 && counts[3] == 1;
        if (!ok) System.exit(1);
    }
}




