package cn.xinji.agentplatform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyV0BoundaryFilterTest {
    @Test
    void ordinaryAndProductionProfilesDenyLegacyApisBeforeBusinessHandlers() throws Exception {
        for (String profile : new String[] {"", "dev-synthetic", "prod", "prod,test-synthetic"}) {
            MockEnvironment environment = new MockEnvironment();
            if (!profile.isEmpty()) environment.setActiveProfiles(profile.split(","));
            for (String path : new String[] {
                    "/", "/app", "/app/consultation", "/app/index.html",
                    "/api/v0/consultations", "/api/v0/patent-search-tasks", "/api/v0/enforcement/work-items",
                    "/api/v0/adjudication/work-items", "/api/pilot/patent-assistant/status"}) {
                assertDenied(environment, path);
            }
        }
    }

    @Test
    void historicalProfilesRetainOnlyTheirExistingTestHandlers() throws Exception {
        for (String profile : new String[] {"test-synthetic", "public-local-test", "dm8-integration"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            MockHttpServletRequest request = request("/api/v0/consultations");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            new LegacyV0BoundaryFilter(environment).doFilter(request, response, chain);
            assertThat(chain.getRequest()).isSameAs(request);
        }
    }

    @Test
    void unrelatedRoutesRemainUnaffected() throws Exception {
        MockHttpServletRequest request = request("/v2/consultation/tasks");
        MockFilterChain chain = new MockFilterChain();
        new LegacyV0BoundaryFilter(new MockEnvironment()).doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void ordinaryProfileBlocksTheLegacyFrontendBeforeControllerForwarding() throws Exception {
        MockMvcBuilders.standaloneSetup(new ReactFrontendController())
                .addFilters(new LegacyV0BoundaryFilter(new MockEnvironment()))
                .build().perform(get("/app/consultation"))
                .andExpect(status().isNotFound());
    }

    private static void assertDenied(MockEnvironment environment, String path) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LegacyV0BoundaryFilter(environment).doFilter(request(path), response, chain);
        assertThat(response.getStatus()).as(path).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
