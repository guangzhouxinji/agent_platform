package cn.xinji.agentplatform.enforcement.documentrule;

import java.util.Map;
import java.util.Set;

/** Engineering-test namespace only: prevents the read-only historical runtime from sharing the writable fixture identity. */
final class DocumentRuleRuntimeEnvironment {
    static final String PREFIX = "AGENT_ENF_RULE_";
    private static final Set<String> CONNECTION = Set.of("ENF_ACCEPTANCE_BUSINESS_URL", "ENF_ACCEPTANCE_BUSINESS_USER", "ENF_ACCEPTANCE_BUSINESS_PASSWORD");
    private DocumentRuleRuntimeEnvironment() { }
    static String value(String name, Map<String, String> environment) {
        if (!CONNECTION.contains(name)) return environment.get(name);
        boolean isolated = environment.keySet().stream().anyMatch(key -> key.startsWith(PREFIX));
        if (!isolated) return environment.get(name);
        for (String key : CONNECTION) {
            String actual = environment.get(PREFIX + key);
            if (actual == null || actual.isBlank()) throw new IllegalStateException(PREFIX + key + " required; partial namespace cannot fall back to writable fixture identity");
        }
        if (!"jdbc:dm://127.0.0.1:5311".equals(environment.get(PREFIX + "ENF_ACCEPTANCE_BUSINESS_URL"))
                || !"ENFACC_BIZ".equals(environment.get(PREFIX + "ENF_ACCEPTANCE_BUSINESS_USER")))
            throw new IllegalStateException("Namespaced retained runtime permits only explicitly authorized read-only ENF 5311/ENFACC_BIZ; writable store/fixture must use its own base namespace");
        return environment.get(PREFIX + name);
    }
}
