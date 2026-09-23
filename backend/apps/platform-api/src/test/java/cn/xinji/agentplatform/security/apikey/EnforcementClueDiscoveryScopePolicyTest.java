package cn.xinji.agentplatform.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnforcementClueDiscoveryScopePolicyTest {
    private final ApiScopePolicy policy = new ApiScopePolicy();

    @Test void separatesReadWorkAndReviewOperationsAndRequiresTrustedOrganization() {
        assertScope("GET", "/api/v0/enforcement/discovery-tasks", "enforcement:read");
        assertScope("POST", "/api/v0/enforcement/discovery-tasks", "enforcement:work");
        assertScope("GET", "/api/v0/enforcement/leads/lead-1", "enforcement:read");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/start-verification", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/leads/duplicate-links", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/transfer", "enforcement:review");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/exclude", "enforcement:review");
        assertScope("POST", "/api/v0/enforcement/leads/duplicate-links/link-1/confirm", "enforcement:review");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/parse-tasks", "enforcement:work");
        assertScope("GET", "/api/v0/enforcement/leads/lead-1/parse-tasks", "enforcement:read");
        assertScope("GET", "/api/v0/enforcement/leads/lead-1/parses/2", "enforcement:read");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/parses/2/revisions", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/parses/2/confirm", "enforcement:review");
        assertScope("POST", "/api/v0/enforcement/leads/lead-1/parses/2/return", "enforcement:review");
        assertScope("GET", "/api/v0/enforcement/evidence", "enforcement:read");
        assertScope("GET", "/api/v0/enforcement/evidence/evidence-1/versions/1/download", "enforcement:read");
        assertScope("POST", "/api/v0/enforcement/evidence", "enforcement:work");
        assertScope("PUT", "/api/v0/enforcement/evidence/evidence-1", "enforcement:work");
        assertScope("POST", "/api/v0/enforcement/evidence/evidence-1/status", "enforcement:review");
    }

    private void assertScope(String method, String path, String expected) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        assertThat(policy.protects(request)).isTrue();
        assertThat(policy.requiresOrganization(request)).isTrue();
        assertThat(policy.requiredScope(request)).isEqualTo(expected);
    }
}
