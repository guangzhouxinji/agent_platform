package cn.xinji.agentplatform.security.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyAuthenticationFilterTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T00:00:00Z"), ZoneOffset.UTC);

    @Test void requiresKeyOnProtectedApisAndLeavesOtherPathsAlone() throws Exception {
        Fixture fixture = fixture(List.of("consultation:submit"), 10, 100);
        MockHttpServletRequest protectedRequest = request("POST", "/api/v0/consultations");
        MockHttpServletResponse protectedResponse = new MockHttpServletResponse();
        AtomicBoolean protectedCalled = new AtomicBoolean();

        fixture.filter().doFilter(protectedRequest, protectedResponse, (request, response) -> protectedCalled.set(true));

        assertThat(protectedResponse.getStatus()).isEqualTo(401);
        assertThat(protectedResponse.getContentAsString()).contains("API_KEY_REQUIRED");
        assertThat(protectedCalled).isFalse();

        MockHttpServletResponse enforcementResponse = new MockHttpServletResponse();
        fixture.filter().doFilter(request("GET", "/api/v0/enforcement/work-items/id"), enforcementResponse,
                (request, response) -> { throw new AssertionError("unauthenticated request reached application"); });
        assertThat(enforcementResponse.getStatus()).isEqualTo(401);

        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();
        fixture.filter().doFilter(request("GET", "/api/v0/development-sources/enforcement/clues"), catalogResponse,
                (request, response) -> { throw new AssertionError("unauthenticated catalog request reached application"); });
        assertThat(catalogResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest publicRequest = request("GET", "/app/consultation");
        AtomicBoolean publicCalled = new AtomicBoolean();
        fixture.filter().doFilter(publicRequest, new MockHttpServletResponse(), (request, response) -> publicCalled.set(true));
        assertThat(publicCalled).isTrue();
    }

    @Test void deniesMissingScopeWithoutInvokingApplication() throws Exception {
        Fixture fixture = fixture(List.of("consultation:read"), 10, 100);
        MockHttpServletRequest request = authorized("GET", "/api/v0/consultations/id/source-links/link");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        fixture.filter().doFilter(request, response, (ignoredRequest, ignoredResponse) -> called.set(true));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("INSUFFICIENT_SCOPE");
        assertThat(called).isFalse();
        assertThat(fixture.events()).extracting(ApiAccessAuditSink.ApiAccessAuditEvent::outcome)
                .containsExactly("SCOPE_DENIED");
    }

    @Test void replacesSpoofedIdentityAndPreservesSafeRequestId() throws Exception {
        Fixture fixture = fixture(List.of("consultation:submit"), 10, 100);
        MockHttpServletRequest request = authorized("POST", "/api/v0/consultations");
        request.addHeader("X-V0-Requester", "spoofed-user");
        request.addHeader("X-Request-Id", "partner-request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requester = new AtomicReference<>();
        AtomicReference<String> client = new AtomicReference<>();

        fixture.filter().doFilter(request, response, (wrapped, ignored) -> {
            requester.set(((jakarta.servlet.http.HttpServletRequest) wrapped).getHeader("X-V0-Requester"));
            client.set(((jakarta.servlet.http.HttpServletRequest) wrapped).getHeader("X-Api-Client-Id"));
        });

        assertThat(requester.get()).isEqualTo("v0-authorized-user");
        assertThat(client.get()).isEqualTo("partner-client");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("partner-request-42");
        assertThat(fixture.events()).singleElement().satisfies(event -> {
            assertThat(event.clientId()).isEqualTo("partner-client");
            assertThat(event.keyId()).isEqualTo("partner-key");
            assertThat(event.outcome()).isEqualTo("SUCCEEDED");
        });
    }

    @Test void replacesSpoofedEnforcementIdentityAndOrganization() throws Exception {
        Fixture fixture = fixture(List.of("enforcement:work"), 10, 100);
        MockHttpServletRequest request = authorized("POST", "/api/v0/enforcement/work-items");
        request.addHeader("X-V0-Actor", "spoofed-user");
        request.addHeader("X-V0-Organization", "spoofed-org");
        AtomicReference<String> actor = new AtomicReference<>();
        AtomicReference<String> organization = new AtomicReference<>();

        fixture.filter().doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            var trusted = (jakarta.servlet.http.HttpServletRequest) wrapped;
            actor.set(trusted.getHeader("X-V0-Actor"));
            organization.set(trusted.getHeader("X-V0-Organization"));
        });

        assertThat(actor.get()).isEqualTo("v0-authorized-user");
        assertThat(organization.get()).isEqualTo("org-v0");
    }

    @Test void rejectsEnforcementCreateWhenCredentialHasNoOrganization() throws Exception {
        Fixture fixture = fixture(List.of("enforcement:work"), 10, 100, false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        fixture.filter().doFilter(authorized("POST", "/api/v0/enforcement/work-items"), response,
                (request, ignored) -> { throw new AssertionError("organization-less credential reached application"); });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("ORGANIZATION_REQUIRED");
    }

    @Test void enforcesMinuteLimitAndDoesNotExposeAuthorizationInAudit() throws Exception {
        Fixture fixture = fixture(List.of("consultation:submit"), 1, 100);
        fixture.filter().doFilter(authorized("POST", "/api/v0/consultations"),
                new MockHttpServletResponse(), (request, response) -> { });
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        fixture.filter().doFilter(authorized("POST", "/api/v0/consultations"), rejected,
                (request, response) -> { throw new AssertionError("rate-limited request reached application"); });

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("RATE_LIMITED");
        assertThat(fixture.events()).allSatisfy(event ->
                assertThat(event.toString()).doesNotContain(SECRET).doesNotContain("Authorization"));
    }

    @Test void enforcesDailyLimitIndependentlyFromMinuteLimit() throws Exception {
        Fixture fixture = fixture(List.of("consultation:submit"), 100, 1);
        fixture.filter().doFilter(authorized("POST", "/api/v0/consultations"),
                new MockHttpServletResponse(), (request, response) -> { });
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        fixture.filter().doFilter(authorized("POST", "/api/v0/consultations"), rejected,
                (request, response) -> { throw new AssertionError("daily-limited request reached application"); });

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(fixture.events()).extracting(ApiAccessAuditSink.ApiAccessAuditEvent::outcome)
                .containsExactly("SUCCEEDED", "RATE_LIMITED");
    }

    private static Fixture fixture(List<String> scopes, int minuteLimit, int dayLimit) {
        return fixture(scopes, minuteLimit, dayLimit, true);
    }

    private static Fixture fixture(List<String> scopes, int minuteLimit, int dayLimit, boolean withOrganization) {
        ApiAccessProperties.Credential credential = new ApiAccessProperties.Credential();
        credential.setKeyId("partner-key");
        credential.setSecretSha256(ApiCredentialRegistry.hashSecret(SECRET));
        credential.setClientId("partner-client");
        credential.setPrincipalRef("v0-authorized-user");
        if (withOrganization) credential.setOrganizationRef("org-v0");
        credential.setScopes(scopes);
        credential.setRequestsPerMinute(minuteLimit);
        credential.setRequestsPerDay(dayLimit);
        ApiAccessProperties properties = new ApiAccessProperties();
        properties.setCredentials(List.of(credential));
        List<ApiAccessAuditSink.ApiAccessAuditEvent> events = new ArrayList<>();
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(new ApiCredentialRegistry(properties),
                new ApiScopePolicy(), new ApiRateLimiter(), events::add, new ObjectMapper(), CLOCK);
        return new Fixture(filter, events);
    }

    private static MockHttpServletRequest authorized(String method, String path) {
        MockHttpServletRequest request = request(method, path);
        request.addHeader("Authorization", "Bearer xj_partner-key." + SECRET);
        return request;
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private record Fixture(ApiKeyAuthenticationFilter filter,
                           List<ApiAccessAuditSink.ApiAccessAuditEvent> events) { }
}
