import java.util.*;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.jupiter.engine.JupiterTestEngine;

public class EngineRunner {
  public static void main(String[] args) throws Exception {
    var selectors = new ArrayList<DiscoverySelector>();
    for (String name : args) selectors.add(DiscoverySelectors.selectClass(Class.forName(name)));
    var request = new EngineDiscoveryRequest() {
      public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type) {
        return selectors.stream().filter(type::isInstance).map(type::cast).toList();
      }
      public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type) { return List.of(); }
      public ConfigurationParameters getConfigurationParameters() { return new ConfigurationParameters() {
        public Optional<String> get(String key) { return Optional.empty(); }
        public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }
        public int size() { return 0; }
        public Set<String> keySet() { return Set.of(); }
      }; }
    };
    var engine = new JupiterTestEngine();
    var root = engine.discover(request, UniqueId.forEngine(engine.getId()));
    int[] counts = new int[3];
    var listener = new EngineExecutionListener() {
      public void executionSkipped(TestDescriptor d, String reason) {
        if (d.isTest()) counts[2]++;
        System.out.println("SKIPPED " + d.getDisplayName() + " " + reason);
      }
      public void executionFinished(TestDescriptor d, TestExecutionResult r) {
        if (d.isTest()) counts[0]++;
        if (r.getStatus() == TestExecutionResult.Status.FAILED) {
          counts[1]++; r.getThrowable().ifPresent(Throwable::printStackTrace);
        }
        System.out.println(r.getStatus() + " " + d.getDisplayName());
      }
    };
    engine.execute(new ExecutionRequest(root, listener, request.getConfigurationParameters()));
    System.out.printf("TESTS=%d FAILURES=%d SKIPPED=%d%n", counts[0], counts[1], counts[2]);
    if(counts[0] == 0 || counts[1] != 0 || counts[2] != 0) System.exit(1);
  }
}
