package cn.xinji.agentplatform.enforcement.documentrule;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DocumentRuleRuntimeEnvironmentTest {
 @Test void explicitLegacyConnectionStillWorksWithoutNamespace(){
  assertEquals("jdbc:dm://127.0.0.1:5311",DocumentRuleRuntimeEnvironment.value("ENF_ACCEPTANCE_BUSINESS_URL",Map.of("ENF_ACCEPTANCE_BUSINESS_URL","jdbc:dm://127.0.0.1:5311")));
 }
 @Test void readOnlyRuntimeNeverUsesWritableFixtureConnectionWhenNamespaced(){
  var env=Map.of("ENF_ACCEPTANCE_BUSINESS_URL","jdbc:dm://127.0.0.1:5491","ENF_ACCEPTANCE_BUSINESS_USER","ENFREG20261007","AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_URL","jdbc:dm://127.0.0.1:5311","AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_USER","ENFACC_BIZ","AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_PASSWORD","engineering-unit-placeholder-not-a-real-secret");
  assertEquals("jdbc:dm://127.0.0.1:5311",DocumentRuleRuntimeEnvironment.value("ENF_ACCEPTANCE_BUSINESS_URL",env));
  assertEquals("ENFACC_BIZ",DocumentRuleRuntimeEnvironment.value("ENF_ACCEPTANCE_BUSINESS_USER",env));
 }
 @Test void partialNamespaceCannotReadLegacyPassword(){
  var env=Map.of("AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_URL","jdbc:dm://127.0.0.1:5311","ENF_ACCEPTANCE_BUSINESS_PASSWORD","legacy-test-placeholder");
  var failure=assertThrows(IllegalStateException.class,()->DocumentRuleRuntimeEnvironment.value("ENF_ACCEPTANCE_BUSINESS_PASSWORD",env));
  assertTrue(failure.getMessage().contains("partial namespace cannot fall back"));
 }
 @Test void currentConsultationEndpointCannotBeInjectedIntoRetainedReadOnlyRuntime(){
  var env=Map.of("AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_URL","jdbc:dm://127.0.0.1:5301","AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_USER","ENFACC_BIZ","AGENT_ENF_RULE_ENF_ACCEPTANCE_BUSINESS_PASSWORD","engineering-unit-placeholder");
  var failure=assertThrows(IllegalStateException.class,()->DocumentRuleRuntimeEnvironment.value("ENF_ACCEPTANCE_BUSINESS_URL",env));
  assertTrue(failure.getMessage().contains("only explicitly authorized read-only ENF 5311"));
 }
 @Test void sourceFilesKeepTheirExistingContract(){
  assertEquals("existing-original-manifest.json",DocumentRuleRuntimeEnvironment.value("ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST",Map.of("ENF_DOCUMENT_RULE_NEGATIVE_MANIFEST","existing-original-manifest.json")));
 }
}
