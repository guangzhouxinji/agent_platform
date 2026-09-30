package cn.xinji.agentplatform.security.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class V2ConsultationBoundaryFilterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC);
    private static final String PROBE = "/v2/consultation/_boundary/identity";

    @Test void productIdentityOnlyPassesExactTaskReadToItsOwnSecurityChain() throws Exception {
        var environment = new MockEnvironment();
        environment.setProperty("product.identity.enabled", "true");
        var filter = new V2ConsultationBoundaryFilter(new DevelopmentBoundaryProperties(), null,
                environment, CLOCK);
        String task = "/v2/consultation/tasks/01234567-89ab-cdef-0123-456789abcdef";
        assertAllowed(filter, "GET", task, "Bearer product-resource-chain-checks-this");
        assertDenied(filter, "POST", task, null, 404);
        assertDenied(filter, "GET", task + "/messages", null, 404);
        assertDenied(filter, "GET", task + "%2fmessages", null, 404);
    }

    @Test void disabledConfigurationAndUnknownRoutesNeverReachApplication() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test-synthetic");
        var disabled = new V2ConsultationBoundaryFilter(properties, null, environment, CLOCK);
        assertDenied(disabled, "GET", PROBE, DevelopmentBoundaryPolicyTest.token("human-a"), 404);

        properties.setEnabled(true);
        var enabled = new V2ConsultationBoundaryFilter(properties, DevelopmentBoundaryPolicyTest.subjects(),
                environment, CLOCK);
        assertDenied(enabled, "POST", PROBE, DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(enabled, "GET", "/v2/consultation/tasks", DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(enabled, "GET", "/v2/consultation/_boundary%2fidentity",
                DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(enabled, "GET", "/v2/%63onsultation/_boundary/identity",
                DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(enabled, "GET", "/V2/Consultation/_boundary/identity",
                DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(enabled, "GET", "/v2/consultation/_boundary/../_boundary/identity",
                DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        environment.setActiveProfiles("dev");
        assertDenied(new V2ConsultationBoundaryFilter(properties, DevelopmentBoundaryPolicyTest.subjects(),
                environment, CLOCK), "GET", PROBE, DevelopmentBoundaryPolicyTest.token("human-a"), 404);
    }

    @Test void rejectsMissingCredentialAndAgentButIgnoresSpoofedHeaders() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        properties.setEnabled(true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test-synthetic");
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var filter = new V2ConsultationBoundaryFilter(properties, subjects, environment, CLOCK);
        assertDenied(filter, "GET", PROBE, null, 401);
        assertDenied(filter, "GET", PROBE, DevelopmentBoundaryPolicyTest.token("agent"), 403);
        assertDenied(filter, "GET", PROBE, DevelopmentBoundaryPolicyTest.token("job"), 403);

        var request = new MockHttpServletRequest("GET", PROBE);
        request.addHeader("Authorization", DevelopmentBoundaryPolicyTest.token("human-a"));
        request.addHeader("X-V0-Requester", "dev-con-b");
        request.addHeader("X-V0-Actor", "dev-con-b");
        request.addHeader("X-V0-Organization", "other-org");
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (wrapped, ignored) -> {
            invoked.set(true);
            var subject = (DevelopmentSubjectPolicy.Subject) wrapped.getAttribute(
                    V2ConsultationBoundaryFilter.SUBJECT_ATTRIBUTE);
            assertThat(subject.stableSubjectId()).isEqualTo("dev-con-a");
            assertThat(subject.organizationRef()).isEqualTo("dev-con-isolated");
        });
        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        subjects.revoke("human-a");
        assertDenied(filter, "GET", PROBE, DevelopmentBoundaryPolicyTest.token("human-a"), 401);
    }

    @Test void taskRoutesAreExplicitAndSeparateHumanAgentAndJob() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        properties.setEnabled(true);
        properties.setTaskCoreEnabled(true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("test-synthetic");
        var filter = new V2ConsultationBoundaryFilter(properties, DevelopmentBoundaryPolicyTest.subjects(),
                environment, CLOCK);
        String human = DevelopmentBoundaryPolicyTest.token("human-a");
        String agent = DevelopmentBoundaryPolicyTest.token("agent");
        String job = DevelopmentBoundaryPolicyTest.token("job");
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        assertAllowed(filter, "POST", "/v2/consultation/tasks", human);
        assertAllowed(filter, "POST", "/v2/consultation/tasks/" + id + "/messages", human);
        assertAllowed(filter, "GET", "/v2/consultation/tasks/" + id, human);
        assertAllowed(filter, "POST", "/v2/internal/consultation/attempts/" + id + "/lease", agent);
        assertAllowed(filter, "POST", "/v2/internal/consultation/retention/reconcile", job);
        assertDenied(filter, "POST", "/v2/consultation/tasks", agent, 403);
        assertDenied(filter, "POST", "/v2/internal/consultation/retention/reconcile", human, 403);
        assertDenied(filter, "POST", "/v2/internal/consultation/attempts/" + id + "/checkpoint", job, 403);
        assertDenied(filter, "GET", "/v2/consultation/tasks/" + id + "/messages", human, 404);
        assertDenied(filter, "POST", "/v2/consultation/tasks/" + id + "/../cancel", human, 404);
        assertDenied(filter, "POST", "/v2/consultation/tasks/" + id + "%2fcancel", human, 404);
        assertDenied(filter, "GET", "/v2/consultation/tasks/------------------------------------", human, 404);
    }

    @Test void adjudicationV2RoutesRemainClosedDespiteDevelopmentCredentialsAndV0Headers() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        properties.setTaskCoreEnabled(true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        var disabled = new V2ConsultationBoundaryFilter(properties, null, environment, CLOCK);
        assertDenied(disabled, "GET", "/v2/adjudication/cases/DEV-ADJ-CASE-001/claims",
                DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        properties.setEnabled(true);
        var filter = new V2ConsultationBoundaryFilter(properties, DevelopmentBoundaryPolicyTest.subjects(),
                environment, CLOCK);

        for (String method : new String[] { "GET", "POST" }) {
            for (String path : new String[] { "/v2/adjudication/cases/DEV-ADJ-CASE-001/claims",
                    "/v2/adjudication/work-items", "/v2/adjudication/cases%2fDEV-ADJ-CASE-001" }) {
                for (String authorization : new String[] { null, DevelopmentBoundaryPolicyTest.token("human-a"),
                        DevelopmentBoundaryPolicyTest.token("agent"), DevelopmentBoundaryPolicyTest.token("job") }) {
                    var request = new MockHttpServletRequest(method, path);
                    if (authorization != null) request.addHeader("Authorization", authorization);
                    request.addHeader("X-V0-Actor", "v0-adjudication-reviewer");
                    request.addHeader("X-V0-Organization", "dev-adj-isolated");
                    var response = new MockHttpServletResponse();
                    AtomicBoolean invoked = new AtomicBoolean();
                    filter.doFilter(request, response, (ignored, ignoredResponse) -> invoked.set(true));
                    assertThat(response.getStatus()).as(method + " " + path).isEqualTo(404);
                    assertThat(response.getContentAsString()).isEmpty();
                    assertThat(invoked).isFalse();
                }
            }
        }
    }

    @Test void developmentProfileCannotExposeHistoricalConsultationRoutes() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        properties.setEnabled(true);
        properties.setTaskCoreEnabled(true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        var filter = new V2ConsultationBoundaryFilter(properties, DevelopmentBoundaryPolicyTest.subjects(),
                environment, CLOCK);
        assertDenied(filter, "POST", "/v2/consultation/tasks", DevelopmentBoundaryPolicyTest.token("human-a"), 404);
        assertDenied(filter, "GET", PROBE, DevelopmentBoundaryPolicyTest.token("human-a"), 404);
    }

    @Test void enforcementV2RoutesRemainClosedDespiteDevelopmentCredentialsAndV0Headers() throws Exception {
        var properties = new DevelopmentBoundaryProperties();
        properties.setTaskCoreEnabled(true);
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev-synthetic");
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        String[] paths = { "/v2/enforcement/dev-material/jobs",
                "/v2/enforcement/dev-material/jobs/" + id,
                "/v2/enforcement/dev-material/jobs/" + id + "/projections",
                "/v2/enforcement/dev-material/jobs/" + id + "/cancel",
                "/v2/internal/enforcement/dev-material/retention/reconcile",
                "/v2/enforcement/dev-material/jobs%2f" + id };
        for (boolean consultationEnabled : new boolean[] { false, true }) {
            properties.setEnabled(consultationEnabled);
            var filter = new V2ConsultationBoundaryFilter(properties,
                    consultationEnabled ? DevelopmentBoundaryPolicyTest.subjects() : null, environment, CLOCK);
            for (String method : new String[] { "GET", "POST" }) {
                for (String path : paths) {
                    for (String authorization : new String[] { null, DevelopmentBoundaryPolicyTest.token("human-a"),
                            DevelopmentBoundaryPolicyTest.token("agent"), DevelopmentBoundaryPolicyTest.token("job") }) {
                        var request = new MockHttpServletRequest(method, path);
                        if (authorization != null) request.addHeader("Authorization", authorization);
                        request.addHeader("X-V0-Actor", "v0-enforcement-reviewer");
                        request.addHeader("X-V0-Organization", "dev-enf-isolated");
                        var response = new MockHttpServletResponse();
                        AtomicBoolean invoked = new AtomicBoolean();
                        filter.doFilter(request, response, (ignored, ignoredResponse) -> invoked.set(true));
                        assertThat(response.getStatus()).as(method + " " + path).isEqualTo(404);
                        assertThat(response.getContentAsString()).isEmpty();
                        assertThat(invoked).isFalse();
                    }
                }
            }
        }
    }

    private static void assertAllowed(V2ConsultationBoundaryFilter filter, String method, String path,
                                      String authorization) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (ignored, ignoredResponse) -> invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    private static void assertDenied(V2ConsultationBoundaryFilter filter, String method, String path,
                                     String authorization, int status) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        var response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(request, response, (ignored, ignoredResponse) -> invoked.set(true));
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(invoked).isFalse();
    }
}
