import java.util.*;
import org.junit.platform.engine.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.jupiter.engine.JupiterTestEngine;

/** Selects only the actual product security test. No suite scan or model/deployment action. */
public final class BoundaryJupiterRunner {
    public static void main(String[] args) {
        var selector=DiscoverySelectors.selectClass("cn.xinji.agentplatform.security.product.ProductComparisonRealSecurityChainTest");
        ConfigurationParameters config=new ConfigurationParameters() {
            public Optional<String> get(String key){return Optional.empty();}
            public Optional<Boolean> getBoolean(String key){return Optional.empty();}
            public int size(){return 0;}
            public Set<String> keySet(){return Set.of();}
        };
        EngineDiscoveryRequest request=new EngineDiscoveryRequest() {
            public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> type){return type.isInstance(selector)?List.of(type.cast(selector)):List.of();}
            public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> type){return List.of();}
            public ConfigurationParameters getConfigurationParameters(){return config;}
        };
        var engine=new JupiterTestEngine();var discovered=engine.discover(request,UniqueId.forEngine(engine.getId()));
        int[] counts=new int[3];
        engine.execute(new ExecutionRequest(discovered,new EngineExecutionListener() {
            public void executionSkipped(TestDescriptor d,String reason){if(d.isTest())counts[2]++;System.out.println("SKIPPED "+d.getDisplayName()+" "+reason);}
            public void executionFinished(TestDescriptor d,TestExecutionResult r) {
                if(r.getStatus()==TestExecutionResult.Status.FAILED){counts[1]++;r.getThrowable().orElseThrow().printStackTrace(System.out);}
                if(d.isTest()&&r.getStatus()==TestExecutionResult.Status.SUCCESSFUL){counts[0]++;System.out.println("PASSED "+d.getDisplayName());}
            }
        },config));
        System.out.printf("successfulTests=%d failedDescriptors=%d skippedTests=%d scope=REAL_HTTPS_ISSUER_TITLE_NEGATIVE_BOUNDARIES_ONLY%n",counts[0],counts[1],counts[2]);
        if(counts[0]!=1||counts[1]!=0||counts[2]!=0)System.exit(1);
    }
}
