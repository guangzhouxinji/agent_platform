import java.util.*;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
public class RepairJupiterRunner {
    public static void main(String[] args) {
        System.out.println("isolation.evidence.dir="+System.getProperty("isolation.evidence.dir"));
        System.out.println("isolation.jdbc.evidence.dir="+System.getProperty("isolation.jdbc.evidence.dir"));
        var selectors=List.of("ProductHistoricalResultIsolationTest","ProductHistoricalResultIsolationJdbcTest",
                "ProductModelTurnRecoveryVerifierTest").stream().map(n->DiscoverySelectors.selectClass(
                    "cn.xinji.agentplatform.security.product."+n)).toList();
        ConfigurationParameters parameters=new ConfigurationParameters() {
            public Optional<String> get(String key){return Optional.empty();}
            public Optional<Boolean> getBoolean(String key){return Optional.empty();}
            public int size(){return 0;} public Set<String> keySet(){return Set.of();}
        };
        EngineDiscoveryRequest request=new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type){return selectors.stream()
                .filter(type::isInstance).map(type::cast).toList();}
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type){return List.of();}
            public ConfigurationParameters getConfigurationParameters(){return parameters;}
        };
        var engine=new JupiterTestEngine();var root=engine.discover(request,UniqueId.forEngine(engine.getId()));
        int[] count={0,0,0};
        engine.execute(new ExecutionRequest(root,new EngineExecutionListener() {
            public void executionSkipped(TestDescriptor d,String reason){if(d.isTest())count[2]++;System.out.println("SKIPPED "+d.getDisplayName());}
            public void executionFinished(TestDescriptor d,TestExecutionResult r) {
                if(d.isTest() && r.getStatus()==TestExecutionResult.Status.SUCCESSFUL){count[0]++;System.out.println("PASSED "+d.getDisplayName());}
                if(r.getStatus()==TestExecutionResult.Status.FAILED){count[1]++;r.getThrowable().orElseThrow().printStackTrace(System.out);}
            }
        },parameters));
        System.out.printf("successfulTests=%d failedDescriptors=%d skippedTests=%d ENGINEERING_TEST_REPAIR_ONLY%n",count[0],count[1],count[2]);
        if(count[0]!=31 || count[1]!=0 || count[2]!=0)System.exit(1);
    }
}

