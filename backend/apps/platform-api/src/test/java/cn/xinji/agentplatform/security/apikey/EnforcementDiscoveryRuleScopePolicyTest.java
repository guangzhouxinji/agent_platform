package cn.xinji.agentplatform.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnforcementDiscoveryRuleScopePolicyTest {
    private final ApiScopePolicy policy = new ApiScopePolicy();

    @Test void separatesReadWorkAndReviewScopesAndRequiresOrganization() {
        assertScope("GET", "/api/v0/enforcement/discovery-rules", "enforcement:read");
        assertScope("POST", "/api/v0/enforcement/discovery-rules", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/discovery-rules/rule-1/versions/1/dry-runs", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/discovery-rules/rule-1/versions/1/publish", "enforcement:review");
        assertScope("POST", "/api/v0/enforcement/discovery-rules/rule-1/versions/1/disable", "enforcement:review");
    }

    private void assertScope(String method, String path, String expected) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        assertThat(policy.protects(request)).isTrue();
        assertThat(policy.requiresOrganization(request)).isTrue();
        assertThat(policy.requiredScope(request)).isEqualTo(expected);
    }
}
